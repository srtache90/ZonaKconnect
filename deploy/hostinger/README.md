# Despliegue Zona K en Hostinger VPS

Paquete listo para subir el proyecto a un **VPS Hostinger** con Docker Compose:

- portal-java
- core-go
- dian-net
- PostgreSQL
- MinIO (reemplazo de S3 / LocalStack)
- Caddy (HTTPS automático)

No incluye mocks (SAP mock, WireMock, Mailpit, LocalStack).

## Costo estimado (2026)

Precios Hostinger VPS (aprox.; varían por promo y renovación):

| Plan | Recursos | Intro (~24 meses) | Renovación típica | ¿Sirve? |
|---|---|---:|---:|---|
| KVM 2 | 2 vCPU / 8 GB / 100 GB | ~USD 7–9 | ~USD 15 | Solo piloto / bajo volumen |
| **KVM 4** | **4 vCPU / 16 GB / 200 GB** | **~USD 10–13** | **~USD 25–29** | **Recomendado producción** |
| KVM 8 | 8 vCPU / 32 GB / 400 GB | ~USD 18–26 | ~USD 50 | Alto volumen + 5 años en disco |

### Total mensual realista

| Concepto | USD/mes |
|---|---:|
| VPS KVM 4 (renovación) | 25–29 |
| Dominio .com (prorrateo) | 1–2 |
| SMTP Google Workspace (ya lo pagan) | 0 extra AWS |
| Backups externos opcionales | 0–10 |
| **Total típico** | **~USD 30–45** |

Comparado con AWS lean (~USD 150–220), Hostinger sale **mucho más barato**, a cambio de menos HA (todo en un solo servidor).

### Disco para 5 años

Con ~1,66 M docs/año y ~200 KB/doc:

- Año 1: ~330 GB
- Año 5: ~1,6 TB

El KVM 4 (200 GB) **no alcanza** para 5 años de PDF/XML/ZIP. Opciones:

1. Empezar en KVM 4 y subir plan / disco cuando crezca.
2. Usar KVM 8 (400 GB) + limpieza/archivo externo.
3. Guardar documentos en **S3/R2 externo** (~USD 5–40/mes según volumen) y el VPS solo para apps + BD.

**Recomendación:** VPS KVM 4 + MinIO al inicio; a los 12–18 meses evaluar disco extra o S3/R2 para retención de 5 años.

## Requisitos del VPS

1. Ubuntu 22.04/24.04
2. Docker + Docker Compose plugin
3. Dominio apuntando (A record) a la IP del VPS
4. Puertos 80 y 443 abiertos
5. Certificado DIAN `.p12` (o `DIAN_MOCK_ENABLED=true` para prueba)

## Instalación rápida en el VPS

```bash
# 1) Docker
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER
# reconectar SSH

# 2) Clonar / subir el repo
cd /opt
sudo mkdir -p zona-k && sudo chown $USER:$USER zona-k
# sube el proyecto aquí (git clone o scp/rsync)

cd /opt/zona-k/deploy/hostinger
cp .env.example .env
nano .env   # completar secretos y DOMAIN

# 3) Certificado DIAN
mkdir -p certs
cp /ruta/a/tu-certificado.p12 certs/dian-certificate.p12

# 4) Desplegar
chmod +x deploy.sh backup.sh
./deploy.sh
```

Portal: `https://TU_DOMINIO`

Login por defecto del seed: `admin` / `admin` (cámbialo en cuanto entre).

## Comandos útiles

```bash
docker compose --env-file .env ps
docker compose --env-file .env logs -f portal-java core-go dian-net
docker compose --env-file .env restart portal-java
./backup.sh
```

## Arquitectura

```text
Internet
   │
   ▼
Caddy :443 (HTTPS Let's Encrypt)
   │
   ▼
portal-java :8081
   ├── core-go :8080
   │     └── dian-net :8080  → DIAN real
   ├── postgres
   └── minio (S3 API)
SMTP Google ← portal (envío de facturas)
```

## Qué NO está en este paquete

- Autoescalado / Multi-AZ
- Secrets Manager de AWS (usa `.env` + archivo `.p12`)
- Archivo automático a 5 años tipo Glacier (hay que programarlo o mover a S3 externo)

## Seguridad mínima

- Cambia todos los passwords de `.env`
- No subas `.env` ni el `.p12` a Git
- Cambia el usuario `admin` del portal
- Corre `./backup.sh` diario (cron) y copia backups fuera del VPS
