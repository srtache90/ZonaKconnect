# Checklist de alta — Zona K en Hostinger VPS

Usar este listado el día que contraten y monten producción.  
Marcar cada ítem al completarlo.

---

## 0. Antes de contratar (1 día antes)

- [ ] Confirmar dominio a usar (ej. `facturacion.zonak.com.co`)
- [ ] Tener acceso DNS del dominio (Hostinger / GoDaddy / Cloudflare, etc.)
- [ ] Tener certificado DIAN `.p12` y su contraseña
- [ ] Tener cuenta Google Workspace / SMTP (usuario + App Password)
- [ ] Definir quién será el responsable técnico del VPS
- [ ] Definir ventana de corte para migrar desde los 2 programas actuales
- [ ] Respaldar configuración actual: resoluciones, prefijos, puntos de venta, rangos

---

## 1. Contratar el VPS

- [ ] Plan: **Hostinger VPS KVM 4** (4 vCPU / 16 GB / 200 GB) — mínimo recomendado
- [ ] SO: **Ubuntu 24.04 LTS** (o 22.04)
- [ ] Región: la más cercana a Colombia disponible (o USA East si latencia DIAN es aceptable)
- [ ] Anotar: **IP pública**, usuario root/SSH, panel Hostinger
- [ ] Activar **backups semanales** del VPS en el panel Hostinger

---

## 2. DNS

- [ ] Crear registro **A**: `facturacion.tudominio.com` → IP del VPS
- [ ] (Opcional) `www` no es necesario para el API/portal
- [ ] Esperar propagación (5–60 min) y verificar:

```bash
nslookup facturacion.tudominio.com
ping facturacion.tudominio.com
```

- [ ] En firewall Hostinger / UFW: abrir **22** (SSH), **80** (HTTP), **443** (HTTPS)
- [ ] Cerrar al público puertos 5432, 8080, 8081, 9000, 9001

---

## 3. Preparar el servidor

Conectarse por SSH y ejecutar:

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y docker.io docker-compose-v2 git curl ufw
sudo usermod -aG docker $USER
# cerrar sesión SSH y volver a entrar
```

- [ ] Docker instalado (`docker --version`)
- [ ] Compose instalado (`docker compose version`)
- [ ] UFW activo:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

- [ ] Carpeta del proyecto:

```bash
sudo mkdir -p /opt/zona-k
sudo chown $USER:$USER /opt/zona-k
```

- [ ] Subir el código (git clone o `rsync`/`scp` desde el PC de desarrollo)

---

## 4. Configurar secretos y dominio

```bash
cd /opt/zona-k/deploy/hostinger
cp .env.example .env
nano .env
```

Completar en `.env`:

- [ ] `DOMAIN=facturacion.tudominio.com`
- [ ] `ACME_EMAIL=correo-real@tudominio.com`
- [ ] `POSTGRES_PASSWORD=` (fuerte, 20+ chars)
- [ ] `JWT_SECRET=` (mínimo 32 chars aleatorios)
- [ ] `JWT_COOKIE_SECURE=true`
- [ ] `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` (fuertes)
- [ ] `AWS_ACCESS_KEY_ID` = mismo usuario MinIO
- [ ] `AWS_SECRET_ACCESS_KEY` = misma password MinIO
- [ ] `MAIL_HOST=smtp.gmail.com`
- [ ] `MAIL_PORT=587`
- [ ] `MAIL_USERNAME=` cuenta Workspace
- [ ] `MAIL_PASSWORD=` App Password de Google
- [ ] `MAIL_FROM=` correo de facturación
- [ ] `DIAN_MOCK_ENABLED=false` (producción real)
- [ ] `DIAN_CERT_PASSWORD=` password del `.p12`

Seguridad:

- [ ] `.env` no se sube a Git (ya está en `.gitignore`)
- [ ] Permisos: `chmod 600 .env`

---

## 5. Certificado DIAN

```bash
mkdir -p /opt/zona-k/deploy/hostinger/certs
cp /ruta/local/certificado.p12 /opt/zona-k/deploy/hostinger/certs/dian-certificate.p12
chmod 600 /opt/zona-k/deploy/hostinger/certs/dian-certificate.p12
```

- [ ] Archivo existe como `certs/dian-certificate.p12`
- [ ] Password en `.env` coincide
- [ ] Vigencia del certificado revisada (no vencido)

> Prueba previa (opcional): `DIAN_MOCK_ENABLED=true` solo para validar que el portal levanta; luego volver a `false`.

---

## 6. Desplegar

```bash
cd /opt/zona-k/deploy/hostinger
chmod +x deploy.sh backup.sh
./deploy.sh
```

Verificar:

- [ ] `docker compose --env-file .env ps` → todos `Up` (migrate/init en `Exited 0`)
- [ ] Portal responde: `https://facturacion.tudominio.com/login`
- [ ] HTTPS válido (candado / certificado Let's Encrypt vía Caddy)
- [ ] Login inicial: `admin` / `admin`
- [ ] **Cambiar password de admin de inmediato**

Logs si algo falla:

```bash
docker compose --env-file .env logs -f portal-java core-go dian-net caddy
```

---

## 7. Configuración funcional (negocio)

En el portal, configurar:

- [ ] Sociedad(es) / NIT / DV
- [ ] Puntos de venta (prefijos Simphony / SAP)
- [ ] Resoluciones DIAN (rangos, vigencia, clave técnica si aplica)
- [ ] Ambiente DIAN correcto (habilitación vs producción)
- [ ] Credenciales de integración SAP (si aplica)
- [ ] Credenciales / API key Simphony → portal
- [ ] Correo de emisión y prueba de envío SMTP
- [ ] Usuario(s) operativos (no usar solo `admin`)

---

## 8. Pruebas de punta a punta

### Simphony
- [ ] Emitir 1 factura de prueba desde una estación
- [ ] Llega al portal / core
- [ ] Respuesta DIAN OK (CUFE / validado)
- [ ] PDF + XML + ZIP descargables
- [ ] Correo llega al cliente de prueba

### SAP
- [ ] Enviar 1 documento de prueba
- [ ] Misma validación: DIAN + archivos + correo

### Reintentos / fallas
- [ ] Probar factura rechazada (si tienen caso controlado) y ver detalle DIAN
- [ ] Confirmar que el equipo sabe reenviar / corregir resolución

---

## 9. Backups y retención 5 años

- [ ] Cron diario de backup en el VPS:

```bash
crontab -e
# todos los días 2:30 AM
30 2 * * * /opt/zona-k/deploy/hostinger/backup.sh >> /var/log/zona-k-backup.log 2>&1
```

- [ ] Copiar backups **fuera** del VPS (OneDrive / S3 / otro disco) al menos 1 vez al día
- [ ] Definir política: documentos vivos 5 años
- [ ] Decidir almacenamiento de archivos a largo plazo:
  - [ ] Opción A: ampliar disco Hostinger cuando se acerque el límite
  - [ ] Opción B (recomendada): MinIO al inicio → migrar/replicar a **S3 o Cloudflare R2**
- [ ] Probar restauración de un backup de Postgres en ambiente de prueba

---

## 10. Monitoreo mínimo

- [ ] UptimeRobot / Better Stack / similar: ping HTTPS cada 5 min
- [ ] Alerta al correo/WhatsApp del responsable si cae el portal
- [ ] Revisar disco semanal: `df -h` (alertar si > 80%)
- [ ] Revisar logs DIAN 1 vez al día la primera semana

```bash
docker compose --env-file .env logs --since 24h dian-net core-go | tail -200
```

---

## 11. Corte / go-live

- [ ] Congelar cambios de resoluciones en los programas viejos
- [ ] Apuntar Simphony al endpoint nuevo de Zona K
- [ ] Apuntar SAP al endpoint nuevo de Zona K
- [ ] Emitir en paralelo 1 día (viejo + nuevo) si es posible, o piloto en 1 local
- [ ] Validar cola del día: cantidad emitida vs esperada
- [ ] Cuando esté estable: cancelar / no renovar los 2 programas actuales
- [ ] Documentar contactos: Hostinger, DIAN, responsable interno, proveedor Simphony/SAP

---

## 12. Seguridad post-alta (mismo día)

- [ ] Password `admin` cambiado
- [ ] SSH solo con llave (desactivar login root por password si aplica)
- [ ] `.env` y `.p12` solo en el servidor
- [ ] Confirmar que MinIO **no** está expuesto a internet
- [ ] Confirmar que Postgres **no** está expuesto a internet
- [ ] Anotar en un gestor de secretos (1Password/Bitwarden) las contraseñas

---

## Criterio de “listo para producción”

Se considera listo cuando:

1. HTTPS OK en el dominio  
2. 1 factura Simphony + 1 SAP validadas por DIAN  
3. PDF/XML/ZIP y correo OK  
4. Backup automático corriendo  
5. Monitoreo de uptime activo  
6. Password admin cambiado  

---

## Contactos / datos a llenar

| Ítem | Valor |
|---|---|
| IP VPS | |
| Dominio | |
| Panel Hostinger | |
| Responsable técnico | |
| Responsable negocio / DIAN | |
| Fecha go-live | |
| Fecha cancelación proveedores actuales | |
