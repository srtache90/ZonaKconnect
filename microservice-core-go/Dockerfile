FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/core ./cmd/api
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/worker ./cmd/worker

FROM alpine:3.20
WORKDIR /app
COPY --from=build /app/core /app/core
COPY --from=build /app/worker /app/worker
EXPOSE 8080
ENTRYPOINT ["/app/core"]
