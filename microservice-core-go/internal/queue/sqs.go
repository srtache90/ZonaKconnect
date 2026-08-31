package queue

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"

	"zonak/microservice-core-go/internal/emission"
)

// SQSPublisher publica trabajos en Amazon SQS / LocalStack.
type SQSPublisher struct {
	client    *sqs.Client
	queueURL  string
	delaySecs int32
}

func NewSQSPublisher(ctx context.Context, queueURL string) (*SQSPublisher, error) {
	queueURL = strings.TrimSpace(queueURL)
	if queueURL == "" {
		return nil, fmt.Errorf("queueURL requerido")
	}

	cfg, err := loadAWSConfig(ctx)
	if err != nil {
		return nil, err
	}

	delay := int32(120)
	if raw := strings.TrimSpace(os.Getenv("EMISSION_RADIAN_SYNC_DELAY_SECONDS")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed >= 0 && parsed <= 900 {
			delay = int32(parsed)
		}
	}

	return &SQSPublisher{
		client:    sqs.NewFromConfig(cfg),
		queueURL:  queueURL,
		delaySecs: delay,
	}, nil
}

func (p *SQSPublisher) Publish(ctx context.Context, job emission.RadianSyncJob) error {
	body, err := json.Marshal(job)
	if err != nil {
		return err
	}
	delay := p.delaySecs
	if strings.EqualFold(strings.TrimSpace(job.Source), "sweep") {
		delay = 0
	}
	_, err = p.client.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:     aws.String(p.queueURL),
		MessageBody:  aws.String(string(body)),
		DelaySeconds: delay,
	})
	return err
}

// SQSConsumer procesa mensajes de la cola.
type SQSConsumer struct {
	client          *sqs.Client
	queueURL        string
	processor       *emission.RadianSyncService
	waitSeconds     int32
	visibilitySecs  int32
	maxBatch        int32
}

func NewSQSConsumer(ctx context.Context, queueURL string, processor *emission.RadianSyncService) (*SQSConsumer, error) {
	cfg, err := loadAWSConfig(ctx)
	if err != nil {
		return nil, err
	}
	return &SQSConsumer{
		client:         sqs.NewFromConfig(cfg),
		queueURL:       strings.TrimSpace(queueURL),
		processor:      processor,
		waitSeconds:    20,
		visibilitySecs: 120,
		maxBatch:       5,
	}, nil
}

func (c *SQSConsumer) Run(ctx context.Context) error {
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}

		output, err := c.client.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:            aws.String(c.queueURL),
			MaxNumberOfMessages: c.maxBatch,
			WaitTimeSeconds:     c.waitSeconds,
			VisibilityTimeout:   c.visibilitySecs,
		})
		if err != nil {
			return err
		}
		if len(output.Messages) == 0 {
			continue
		}

		for _, message := range output.Messages {
			if err := c.handleMessage(ctx, message); err != nil {
				continue
			}
			if message.ReceiptHandle != nil {
				_, _ = c.client.DeleteMessage(ctx, &sqs.DeleteMessageInput{
					QueueUrl:      aws.String(c.queueURL),
					ReceiptHandle: message.ReceiptHandle,
				})
			}
		}
	}
}

func (c *SQSConsumer) handleMessage(ctx context.Context, message types.Message) error {
	if message.Body == nil {
		return fmt.Errorf("mensaje SQS sin body")
	}

	var job emission.RadianSyncJob
	if err := json.Unmarshal([]byte(*message.Body), &job); err != nil {
		return err
	}
	if job.Job == "" {
		job.Job = emission.JobNameRadianSync
	}

	processCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	return c.processor.Process(processCtx, job)
}

func loadAWSConfig(ctx context.Context) (aws.Config, error) {
	region := strings.TrimSpace(os.Getenv("AWS_REGION"))
	if region == "" {
		region = "us-east-1"
	}

	loadOpts := []func(*config.LoadOptions) error{
		config.WithRegion(region),
	}

	if endpoint := strings.TrimSpace(os.Getenv("AWS_ENDPOINT_URL")); endpoint != "" {
		loadOpts = append(loadOpts, config.WithBaseEndpoint(endpoint))
	}

	accessKey := strings.TrimSpace(os.Getenv("AWS_ACCESS_KEY_ID"))
	secretKey := strings.TrimSpace(os.Getenv("AWS_SECRET_ACCESS_KEY"))
	if accessKey != "" && secretKey != "" {
		loadOpts = append(loadOpts, config.WithCredentialsProvider(
			credentials.NewStaticCredentialsProvider(accessKey, secretKey, ""),
		))
	}

	return config.LoadDefaultConfig(ctx, loadOpts...)
}
