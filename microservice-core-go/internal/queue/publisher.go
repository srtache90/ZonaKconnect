package queue

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"zonak/microservice-core-go/internal/emission"
)

// Publisher encola trabajos de sync RADIAN.
type Publisher interface {
	Publish(ctx context.Context, job emission.RadianSyncJob) error
}

// InlinePublisher procesa en goroutine local (fallback sin SQS).
type InlinePublisher struct {
	processor *emission.RadianSyncService
	delay     time.Duration
}

func NewInlinePublisher(processor *emission.RadianSyncService, delay time.Duration) *InlinePublisher {
	if delay <= 0 {
		delay = 2 * time.Minute
	}
	return &InlinePublisher{processor: processor, delay: delay}
}

func (p *InlinePublisher) Publish(ctx context.Context, job emission.RadianSyncJob) error {
	payload, _ := json.Marshal(job)
	log.Printf("radian_sync inline enqueue invoice_id=%s delay=%s payload=%s", job.InvoiceID, p.delay, string(payload))
	go func() {
		timer := time.NewTimer(p.delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		processCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if err := p.processor.Process(processCtx, job); err != nil {
			log.Printf("radian_sync inline error invoice_id=%s: %v", job.InvoiceID, err)
		}
	}()
	return nil
}
