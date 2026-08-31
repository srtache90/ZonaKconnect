package queue

import (
	"context"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"zonak/microservice-core-go/internal/emission"
)

// NewPublisherFromEnv usa SQS si EMISSION_RADIAN_SYNC_QUEUE_URL está definido; si no, inline goroutine.
func NewPublisherFromEnv(ctx context.Context, db *pgxpool.Pool, dianAPIURL string) Publisher {
	processor := emission.NewRadianSyncService(db, dianAPIURL, nil)
	queueURL := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SYNC_QUEUE_URL"))
	if queueURL != "" {
		publisher, err := NewSQSPublisher(ctx, queueURL)
		if err != nil {
			log.Printf("radian_sync SQS publisher unavailable, fallback inline: %v", err)
			return NewInlinePublisher(processor, inlineDelay())
		}
		log.Printf("radian_sync publisher=SQS queue=%s", queueURL)
		return publisher
	}

	log.Printf("radian_sync publisher=inline delay=%s", inlineDelay())
	return NewInlinePublisher(processor, inlineDelay())
}

// NewSweepPublisher crea publisher para el worker (siempre SQS si está configurado).
func NewSweepPublisher(ctx context.Context) (Publisher, error) {
	queueURL := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SYNC_QUEUE_URL"))
	if queueURL == "" {
		return nil, nil
	}
	return NewSQSPublisher(ctx, queueURL)
}

func inlineDelay() time.Duration {
	raw := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SYNC_DELAY_SECONDS"))
	if raw == "" {
		return 2 * time.Minute
	}
	seconds, err := strconv.Atoi(raw)
	if err != nil || seconds < 0 {
		return 2 * time.Minute
	}
	return time.Duration(seconds) * time.Second
}
