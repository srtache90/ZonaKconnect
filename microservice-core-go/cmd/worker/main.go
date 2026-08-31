package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"zonak/microservice-core-go/internal/emission"
	"zonak/microservice-core-go/internal/queue"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	db, err := pgxpool.New(ctx, mustEnv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	dianAPIURL := strings.TrimRight(getenv("DIAN_API_URL", getenv("DIAN_NET_URL", "http://dian-net:8080")), "/")
	processor := emission.NewRadianSyncService(db, dianAPIURL, nil)

	queueURL := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SYNC_QUEUE_URL"))
	if queueURL == "" {
		log.Fatal("EMISSION_RADIAN_SYNC_QUEUE_URL es requerido para core-worker")
	}

	consumer, err := queue.NewSQSConsumer(ctx, queueURL, processor)
	if err != nil {
		log.Fatal(err)
	}

	sweepPublisher, err := queue.NewSweepPublisher(ctx)
	if err != nil {
		log.Fatal(err)
	}

	go runSweepLoop(ctx, db, processor, sweepPublisher)

	log.Printf("core-worker started queue=%s sweep_interval=%s", queueURL, sweepInterval())
	if err := consumer.Run(ctx); err != nil && ctx.Err() == nil {
		log.Fatal(err)
	}
}

func runSweepLoop(ctx context.Context, db *pgxpool.Pool, processor *emission.RadianSyncService, publisher queue.Publisher) {
	ticker := time.NewTicker(sweepInterval())
	defer ticker.Stop()

	runSweep(ctx, db, processor, publisher)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runSweep(ctx, db, processor, publisher)
		}
	}
}

func runSweep(ctx context.Context, db *pgxpool.Pool, processor *emission.RadianSyncService, publisher queue.Publisher) {
	tenantIDs, err := processor.ListActiveTenantIDs(ctx)
	if err != nil {
		log.Printf("radian_sweep list tenants error: %v", err)
		return
	}

	limit := sweepLimit()
	enqueued := 0
	for _, tenantID := range tenantIDs {
		candidates, err := processor.FindOpenDocuments(ctx, &tenantID, limit)
		if err != nil {
			log.Printf("radian_sweep tenant_id=%s error: %v", tenantID, err)
			continue
		}
		for _, candidate := range candidates {
			job := emission.RadianSyncJob{
				Job:             emission.JobNameRadianSync,
				TenantID:        candidate.TenantID,
				EmissionPointID: candidate.EmissionPointID,
				InvoiceID:       candidate.InvoiceID,
				Cufe:            candidate.Cufe,
				Ambiente:        candidate.Ambiente,
				Attempt:         0,
				EnqueuedAt:      time.Now().UTC(),
				Source:          "sweep",
			}
			if publisher != nil {
				if err := publisher.Publish(ctx, job); err != nil {
					log.Printf("radian_sweep publish invoice_id=%s error: %v", candidate.InvoiceID, err)
					continue
				}
			} else {
				processCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
				err := processor.Process(processCtx, job)
				cancel()
				if err != nil {
					log.Printf("radian_sweep process invoice_id=%s error: %v", candidate.InvoiceID, err)
					continue
				}
			}
			enqueued++
		}
	}
	if enqueued > 0 {
		log.Printf("radian_sweep enqueued_or_processed=%d tenants=%d", enqueued, len(tenantIDs))
	}
}

func sweepInterval() time.Duration {
	raw := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SWEEP_INTERVAL"))
	if raw == "" {
		return 4 * time.Hour
	}
	if duration, err := time.ParseDuration(raw); err == nil && duration > 0 {
		return duration
	}
	return 4 * time.Hour
}

func sweepLimit() int {
	raw := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SWEEP_LIMIT"))
	if raw == "" {
		return 50
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return 50
	}
	return value
}

func mustEnv(key string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		log.Fatalf("%s es requerido", key)
	}
	return value
}

func getenv(key, fallback string) string {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value
}
