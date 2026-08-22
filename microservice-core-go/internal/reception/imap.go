package reception

import (
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"strings"
	"time"

	zonakcrypto "zonak/microservice-core-go/internal/crypto"

	"github.com/emersion/go-imap/v2"
	"github.com/emersion/go-imap/v2/imapclient"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jhillyerd/enmime/v2"
)

const (
	maxBodiesPerSync     = 80
	maxMessagesPerFolder = 200
	lookbackDays         = 21
)

type IMAPAccount struct {
	Host     string
	Port     int
	Username string
	Password string
}

type SyncResult struct {
	Messages int      `json:"messages"`
	XMLFound int      `json:"xml_found"`
	Imported int      `json:"imported"`
	Skipped  int      `json:"skipped"`
	Summary  string   `json:"summary"`
	Issues   []string `json:"issues,omitempty"`
}

type Service struct {
	DB         *pgxpool.Pool
	Store      *Store
	JWTSecret  string
	S3Endpoint string // optional LocalStack/custom endpoint
}

func NewService(db *pgxpool.Pool, jwtSecret, s3Endpoint string) *Service {
	return &Service{
		DB:         db,
		Store:      &Store{DB: db},
		JWTSecret:  jwtSecret,
		S3Endpoint: s3Endpoint,
	}
}

func (s *Service) LoadIMAPAccount(ctx context.Context, companyID uuid.UUID) (IMAPAccount, error) {
	var host, user, passEnc *string
	var port *int
	err := s.DB.QueryRow(ctx, `
		SELECT host_imap, puerto_imap, usuario_imap, password_imap_enc
		FROM sociedades
		WHERE id = $1
	`, companyID).Scan(&host, &port, &user, &passEnc)
	if err != nil {
		return IMAPAccount{}, fmt.Errorf("sociedad sin configuración IMAP: %w", err)
	}
	acc := IMAPAccount{}
	if host != nil {
		acc.Host = strings.TrimSpace(*host)
	}
	if port != nil {
		acc.Port = *port
	}
	if user != nil {
		acc.Username = strings.TrimSpace(*user)
	}
	if passEnc != nil && strings.TrimSpace(*passEnc) != "" {
		pass, err := zonakcrypto.DecryptAESGCM(s.JWTSecret, *passEnc)
		if err != nil {
			return IMAPAccount{}, err
		}
		acc.Password = pass
	}
	if acc.Host == "" || acc.Port <= 0 || acc.Username == "" || acc.Password == "" {
		return IMAPAccount{}, fmt.Errorf("IMAP incompleto para la sociedad (host/puerto/usuario/contraseña)")
	}
	return acc, nil
}

func (s *Service) TestIMAP(ctx context.Context, companyID uuid.UUID) (string, error) {
	acc, err := s.LoadIMAPAccount(ctx, companyID)
	if err != nil {
		return "", err
	}
	client, err := dialIMAP(acc)
	if err != nil {
		return "", err
	}
	defer client.Close()
	if err := client.Login(acc.Username, acc.Password).Wait(); err != nil {
		return "", fmt.Errorf("IMAP rechazó credenciales de %s: %w", acc.Username, err)
	}
	selected, err := client.Select("INBOX", nil).Wait()
	if err != nil {
		return "", fmt.Errorf("no se encontró INBOX: %w", err)
	}
	_ = client.Logout().Wait()
	return fmt.Sprintf("Conexión IMAP correcta a %s:%d con el usuario %s. Mensajes en INBOX: %d.",
		acc.Host, acc.Port, acc.Username, selected.NumMessages), nil
}

func (s *Service) SyncIMAP(ctx context.Context, companyID uuid.UUID) (SyncResult, error) {
	acc, err := s.LoadIMAPAccount(ctx, companyID)
	if err != nil {
		return SyncResult{}, err
	}
	client, err := dialIMAP(acc)
	if err != nil {
		return SyncResult{}, err
	}
	defer client.Close()
	if err := client.Login(acc.Username, acc.Password).Wait(); err != nil {
		return SyncResult{}, fmt.Errorf("IMAP rechazó credenciales de %s. Use contraseña de aplicación: %w", acc.Username, err)
	}
	defer func() { _ = client.Logout().Wait() }()

	folders := []string{"INBOX", "[Gmail]/All Mail", "[Gmail]/Todos", "[Google Mail]/All Mail"}
	seen := map[string]struct{}{}
	result := SyncResult{}
	bodies := 0
	sociedadNIT, _ := s.Store.LoadSociedadNIT(ctx, companyID)
	since := time.Now().AddDate(0, 0, -lookbackDays)

	for _, folder := range folders {
		if bodies >= maxBodiesPerSync {
			break
		}
		selected, err := client.Select(folder, nil).Wait()
		if err != nil {
			continue
		}
		if selected.NumMessages == 0 {
			continue
		}

		start := uint32(1)
		if selected.NumMessages > maxMessagesPerFolder {
			start = selected.NumMessages - maxMessagesPerFolder + 1
		}
		var seqSet imap.SeqSet
		seqSet.AddRange(start, selected.NumMessages)

		bodySection := &imap.FetchItemBodySection{}
		msgs, err := client.Fetch(seqSet, &imap.FetchOptions{
			Envelope:    true,
			BodySection: []*imap.FetchItemBodySection{bodySection},
			UID:         true,
		}).Collect()
		if err != nil {
			log.Printf("IMAP fetch folder=%s error=%v", folder, err)
			continue
		}

		for i := len(msgs) - 1; i >= 0 && bodies < maxBodiesPerSync; i-- {
			msg := msgs[i]
			result.Messages++
			if msg.Envelope != nil && !msg.Envelope.Date.IsZero() && msg.Envelope.Date.Before(since) {
				continue
			}
			messageID := ""
			if msg.Envelope != nil {
				messageID = strings.TrimSpace(msg.Envelope.Subject) + "|" + fmt.Sprint(msg.UID)
			}
			if messageID != "" {
				if _, ok := seen[messageID]; ok {
					continue
				}
				seen[messageID] = struct{}{}
			}

			raw, ok := readBody(msg, bodySection)
			if !ok || len(raw) == 0 {
				continue
			}
			if !mightContainFiscal(raw, msg) {
				continue
			}
			bodies++

			pack := extractFromMIME(raw)
			result.XMLFound += len(pack.XMLs)
			for _, xmlDoc := range pack.XMLs {
				okIns, note, err := s.Store.InsertReceivedInvoice(ctx, companyID, xmlDoc, "MAIL_INBOX", pack.PDF, sociedadNIT)
				if err != nil {
					result.Issues = append(result.Issues, err.Error())
					continue
				}
				if okIns {
					result.Imported++
				} else {
					result.Skipped++
					if note != "" {
						result.Issues = append(result.Issues, note)
					}
				}
			}
		}
	}

	result.Summary = fmt.Sprintf(
		"Sync IMAP core: mensajes=%d xml=%d importados=%d omitidos=%d",
		result.Messages, result.XMLFound, result.Imported, result.Skipped,
	)
	return result, nil
}

func dialIMAP(acc IMAPAccount) (*imapclient.Client, error) {
	addr := fmt.Sprintf("%s:%d", acc.Host, acc.Port)
	if acc.Port == 143 {
		return imapclient.DialInsecure(addr, nil)
	}
	return imapclient.DialTLS(addr, &imapclient.Options{
		TLSConfig: &tls.Config{ServerName: acc.Host, MinVersion: tls.VersionTLS12},
	})
}

func readBody(msg *imapclient.FetchMessageBuffer, section *imap.FetchItemBodySection) ([]byte, bool) {
	if msg == nil {
		return nil, false
	}
	data := msg.FindBodySection(section)
	if data == nil {
		return nil, false
	}
	return data, true
}

func mightContainFiscal(raw []byte, msg *imapclient.FetchMessageBuffer) bool {
	lower := strings.ToLower(string(raw))
	if strings.Contains(lower, ".xml") || strings.Contains(lower, ".zip") ||
		strings.Contains(lower, "application/zip") || strings.Contains(lower, "text/xml") ||
		strings.Contains(lower, "application/xml") || strings.Contains(lower, "<invoice") ||
		strings.Contains(lower, "attacheddocument") {
		return true
	}
	if msg != nil && msg.Envelope != nil {
		subj := strings.ToLower(msg.Envelope.Subject)
		if strings.Contains(subj, "evento;") || strings.Count(subj, ";") >= 3 {
			return true
		}
	}
	return false
}

func extractFromMIME(raw []byte) FiscalPackage {
	pack := FiscalPackage{}
	env, err := enmime.ReadEnvelope(bytes.NewReader(raw))
	if err != nil {
		return ExtractFiscalPackage(raw, "")
	}
	parts := append([]*enmime.Part{}, env.Attachments...)
	parts = append(parts, env.Inlines...)
	parts = append(parts, env.OtherParts...)
	for _, p := range parts {
		if p == nil || len(p.Content) == 0 {
			continue
		}
		sub := ExtractFiscalPackage(p.Content, p.FileName)
		pack.XMLs = append(pack.XMLs, sub.XMLs...)
		if len(pack.PDF) == 0 && len(sub.PDF) > 0 {
			pack.PDF = sub.PDF
		}
	}
	if len(pack.XMLs) == 0 && env.Text != "" && IsReceivableUBL(env.Text) {
		pack.XMLs = append(pack.XMLs, env.Text)
	}
	return pack
}
