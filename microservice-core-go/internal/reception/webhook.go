package reception

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/google/uuid"
)

type IncomingEmailWebhook struct {
	TenantID       uuid.UUID `json:"tenant_id"`
	S3Bucket       string    `json:"s3_bucket"`
	S3ObjectKey    string    `json:"s3_object_key"`
	MessageID      string    `json:"message_id"`
	FileName       string    `json:"file_name"`
	ContentBase64  string    `json:"content_base64"` // opcional: bypass S3 (pruebas)
}

type IncomingEmailAcceptedResponse struct {
	Status      string `json:"status"`
	S3ObjectKey string `json:"s3_object_key"`
	Imported    int    `json:"imported,omitempty"`
	Skipped     int    `json:"skipped,omitempty"`
	Async       bool   `json:"async"`
}

func (s *Service) ProcessWebhook(ctx context.Context, tenantID uuid.UUID, webhook IncomingEmailWebhook) (IngestResult, error) {
	var content []byte
	var err error
	fileName := webhook.FileName
	if fileName == "" {
		fileName = webhook.S3ObjectKey
	}

	if strings.TrimSpace(webhook.ContentBase64) != "" {
		content, err = base64.StdEncoding.DecodeString(strings.TrimSpace(webhook.ContentBase64))
		if err != nil {
			return IngestResult{}, fmt.Errorf("content_base64 inválido: %w", err)
		}
	} else {
		if strings.TrimSpace(webhook.S3ObjectKey) == "" {
			return IngestResult{}, fmt.Errorf("s3_object_key requerido")
		}
		content, err = s.downloadFromS3(ctx, webhook.S3Bucket, webhook.S3ObjectKey)
		if err != nil {
			return IngestResult{}, err
		}
	}

	source := "WEBHOOK_S3"
	if webhook.MessageID != "" {
		source = "WEBHOOK_S3:" + webhook.MessageID
	}

	// MIME o paquete fiscal directo
	pack := extractFromMIME(content)
	if len(pack.XMLs) == 0 {
		pack = ExtractFiscalPackage(content, fileName)
	}
	if len(pack.XMLs) == 0 {
		return IngestResult{}, fmt.Errorf("no se encontró XML fiscal en el objeto S3/contenido")
	}

	sociedadNIT, _ := s.Store.LoadSociedadNIT(ctx, tenantID)
	result := IngestResult{}
	for _, xmlDoc := range pack.XMLs {
		ok, note, err := s.Store.InsertReceivedInvoice(ctx, tenantID, xmlDoc, source, pack.PDF, sociedadNIT)
		if err != nil {
			return result, err
		}
		if ok {
			result.Imported++
		} else {
			result.Skipped++
			if note != "" {
				result.Issues = append(result.Issues, note)
			}
		}
	}
	if result.Imported == 0 && result.Skipped == 0 {
		return result, fmt.Errorf("no se importó ningún documento")
	}
	return result, nil
}

func (s *Service) ProcessWebhookAsync(tenantID uuid.UUID, webhook IncomingEmailWebhook) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		result, err := s.ProcessWebhook(ctx, tenantID, webhook)
		if err != nil {
			log.Printf("webhook recepción tenant=%s key=%s error=%v", tenantID, webhook.S3ObjectKey, err)
			return
		}
		log.Printf("webhook recepción tenant=%s key=%s imported=%d skipped=%d",
			tenantID, webhook.S3ObjectKey, result.Imported, result.Skipped)
	}()
}

func (s *Service) downloadFromS3(ctx context.Context, bucket, key string) ([]byte, error) {
	if bucket == "" {
		bucket = getenv("RECEPTION_S3_BUCKET", getenv("S3_BUCKET", ""))
	}
	if bucket == "" {
		return nil, fmt.Errorf("s3_bucket no indicado y RECEPTION_S3_BUCKET/S3_BUCKET vacío")
	}

	cfg, err := loadAWSConfig(ctx, s.S3Endpoint)
	if err != nil {
		return nil, err
	}
	client := s3.NewFromConfig(cfg, func(o *s3.Options) {
		if s.S3Endpoint != "" {
			o.BaseEndpoint = aws.String(s.S3Endpoint)
			o.UsePathStyle = true
		}
	})
	out, err := client.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key),
	})
	if err != nil {
		return nil, fmt.Errorf("GetObject s3://%s/%s: %w", bucket, key, err)
	}
	defer out.Body.Close()
	return io.ReadAll(io.LimitReader(out.Body, 12<<20))
}

func loadAWSConfig(ctx context.Context, endpoint string) (aws.Config, error) {
	region := getenv("AWS_REGION", "us-east-1")
	opts := []func(*config.LoadOptions) error{
		config.WithRegion(region),
	}
	// LocalStack / credenciales explícitas
	ak := os.Getenv("AWS_ACCESS_KEY_ID")
	sk := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if ak != "" && sk != "" {
		opts = append(opts, config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(ak, sk, "")))
	}
	cfg, err := config.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return aws.Config{}, err
	}
	_ = endpoint
	return cfg, nil
}

func getenv(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}
