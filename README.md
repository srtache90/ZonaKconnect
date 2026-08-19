# ZonaKconnect

proyecto documentos electrónicos Zonak-DIAN

# Facturación Electrónica Zona K — Guía para desarrolladores

Guía de onboarding para programadores junior que van a **revisar, probar y ajustar** el frontend (portal web) y el backend (servicios).

---

## 1. ¿Qué es este proyecto?

Sistema de **facturación electrónica DIAN** para Zona K. Permite:

- **Emitir** facturas electrónicas (manual, importación XML, integraciones).
- **Recibir** facturas de proveedores (bandeja RADIAN).
- **Configurar** sociedades, puntos de venta y certificados.
- **Reportar** ventas y exportar medios magnéticos.
- Enviar documentos al motor fiscal **DIAN_NET** (firma / validación), con mock local en desarrollo.

Nombre comercial en UI: **DIAN Connect**.

---

## 2. Arquitectura en una imagen mental

```
Usuario (navegador)
        │
        ▼
┌───────────────────────────┐
│  portal-java (Spring)     │  ← Frontend HTML + Backend web (puerto 8080)
│  Thymeleaf + CSS/JS       │
└─────────────┬─────────────┘
              │ HTTP API
              ▼
┌───────────────────────────┐
│  core-go (Go / Chi)       │  ← API de facturas / persistencia (puerto 8081)
└─────────────┬─────────────┘
              │
       ┌──────┴──────┐
       ▼             ▼
┌────────────┐  ┌────────────┐
│ PostgreSQL │  │  DIAN_NET  │  (.NET, puerto 8090)
│   :5432    │  │  firma XML │
└────────────┘  └─────┬──────┘
                      │ (mock local)
                      ▼
               WireMock :8089

Extras locales:
- LocalStack (S3) :4566
- Mailpit (correo) :8025 UI / :1025 SMTP
- SAP mock :8095
```

### Responsabilidades por pieza

| Componente | Carpeta | Qué hace |
|---|---|---|
| **Portal** | `microservice-portal-java/` | Login, pantallas HTML, menús, reportes, admin, orquestación hacia el core |
| **Core** | `microservice-core-go/` | API REST de facturas, numeración, estados DIAN, guardado en BD |
| **DIAN_NET** | `DIAN_NET/` | Motor de firma / envío fiscal (en local usa mock) |
| **Base de datos** | `database/` | `schema.sql` + migraciones |
| **Mocks** | `mocks/`, WireMock en compose | Simulan DIAN y SAP sin red externa |

> **Importante:** el “frontend” **no es React/Angular**. Son plantillas **Thymeleaf** (HTML en el servidor) con Tailwind CDN y/o Bootstrap, más JS en la misma página.

---

## 3. Requisitos previos

1. **Docker Desktop** instalado y en ejecución (Windows).
2. Git (opcional pero recomendado).
3. Navegador (Chrome / Edge).
4. Editor: Cursor / VS Code.
5. (Opcional) JDK 21 + Maven si compilas el portal fuera de Docker.
6. (Opcional) Go 1.25+ si trabajas el core fuera de Docker.

---

## 4. Cómo levantar el entorno local

Desde la raíz del repo:

```powershell
cd "C:\Users\Jefe-Sistemas\Documents\facturacion electrónica Zona K"
docker compose -f docker-compose.local.yml up -d --build
```

Primera vez puede tardar varios minutos (descarga imágenes y builds).

### Tareas en Cursor / VS Code (sin Docker)

El repo versiona `.vscode/tasks.json` (no `settings.json`: el JDK es local). Terminal → Run Task:

| Tarea | Qué arranca |
|---|---|
| **Arrancar Ecosistema Zona K** | Core Go `:8081` + Portal Java `:8080` en paralelo |
| **Arrancar Ecosistema Zona K (ventanas externas)** | `start-services.ps1` |

Plantilla de secretos de despliegue: `deploy/hostinger/.env.example` (copiar a `.env` fuera de Git).

### URLs útiles

| Servicio | URL | Uso |
|---|---|---|
| Portal web | http://localhost:8080 | UI principal |
| Core API | http://localhost:8081 | API Go (Swagger no obligatorio; se prueba con curl/Postman) |
| DIAN_NET | http://localhost:8090 | Motor fiscal |
| Mock DIAN | http://localhost:8089 | WireMock |
| Mailpit | http://localhost:8025 | Ver correos de prueba |
| Postgres | localhost:5432 | BD (`zona_k_facturacion`) |
| LocalStack S3 | http://localhost:4566 | Almacenamiento local |

### Login del portal

| Usuario | Contraseña |
|---|---|
| `admin` | `admin` |

### Ver que todo está arriba

```powershell
docker compose -f docker-compose.local.yml ps
docker logs zona-k-portal-java --tail 50
docker logs zona-k-core-go --tail 50
```

### Reconstruir solo el portal (lo más común al tocar UI/Java)

```powershell
docker compose -f docker-compose.local.yml up -d --build portal-java
```

### Parar todo

```powershell
docker compose -f docker-compose.local.yml down
```

> Si necesitas borrar también datos de Postgres/LocalStack: `down -v` (borra volúmenes; úsalo solo si sabes lo que haces).

---

## 5. Flujo funcional principal (emisión)

1. Entras a **http://localhost:8080/login** → `admin` / `admin`.
2. Llegas al **Panel de módulos** (`/portal`).
3. Entras a **Emisión de Facturación** (`/portal/invoices`).
4. Eliges **sociedad** y **punto de venta**.
5. Puedes:
   - listar facturas emitidas;
   - ir a **Emitir documento manual** (`/portal/facturacion/manual`);
   - ver **Reportes** (`/portal/emision/reportes`).
6. Al emitir, el portal llama al **core-go**, que numera la factura, la guarda y la envía a **DIAN_NET** (mock en local).
7. El estado DIAN se refleja en la lista (PENDIENTE, ENVIADO, RECHAZADO, etc.).

### Flujo recepción

1. Panel → **Recepción**.
2. Ruta real: `/portal/recepcion/bandeja` (no debe abrirse el listado de emisión).
3. Lista facturas con `emission_point_id IS NULL` (documentos recibidos).
4. Eventos RADIAN (085/086/087/088) están preparados en UI; la sincronización IMAP completa puede estar pendiente.

### Regla de navegación

Cada módulo tiene su **menú lateral propio**. Para cambiar de módulo se usa **Panel de módulos** (`/portal`), no enlaces cruzados en el sidebar.

---

## 6. Frontend (portal) — dónde tocar

### Ubicación

```
microservice-portal-java/src/main/resources/templates/
├── login.html
└── portal/
    ├── dashboard.html              ← Panel de módulos
    ├── invoices.html               ← Emisión (lista)
    ├── factura_manual.html         ← Emisión manual
    ├── recepcion_bandeja.html      ← Recepción
    ├── configuraciones.html
    ├── importar-xml.html
    ├── fragments/
    │   └── portal-nav.html         ← Sidebar / header / bottom nav (MUY IMPORTANTE)
    ├── emision/reportes/           ← Reportes de emisión
    ├── admin/                      ← Sociedades, PV, certificados
    └── reportes/
```

CSS compartido:

```
microservice-portal-java/src/main/resources/static/css/zonak-portal.css
```

### Cómo está armada la UI

- **Thymeleaf**: HTML con atributos `th:*` (ej. `th:href`, `th:each`, `th:text`).
- **Navegación reutilizable** en `portal/fragments/portal-nav.html`:
  - `sidebar(module, activeMenu)`
  - `header`
  - `bottomNav(module, activeMenu)`
  - `shellScripts`
  - `tailwindHead(title)`
- En el controlador se pasan `navModule` y `navActive` para marcar el ítem activo.

Ejemplo de módulos del sidebar:

| `navModule` | Opciones típicas |
|---|---|
| `emision` | Emisión, Reportes, Manual |
| `recepcion` | Bandeja, Histórico, Reportes |
| `configuracion` | General, Sociedades, Puntos, Certificados |
| `reportes` | Inicio / Ventas / Medios magnéticos |

### Checklist al cambiar una pantalla

1. Edita el HTML en `src/main/resources/templates/...` (no solo en `target/`).
2. Si cambias menú, toca `portal-nav.html` y verifica que el controlador pase `navModule` / `navActive`.
3. Reconstruye: `docker compose -f docker-compose.local.yml up -d --build portal-java`.
4. Hard refresh en el navegador: **Ctrl + F5**.
5. Confirma que no te mande a otro módulo por error.

---

## 7. Backend portal (Java) — dónde tocar

### Paquete principal

```
microservice-portal-java/src/main/java/com/zonak/portal/
├── controller/          ← Rutas HTTP del portal (HTML)
├── admin/               ← Sociedades, PV, certificados
├── auth/                ← Login / JWT cookie
├── dto/                 ← Objetos de transferencia
├── recepcion/           ← Modelo/repo de facturas recibidas
├── reports/             ← Reportes y medios magnéticos
├── service/             ← Orquestación, cliente al core, PDF, sesión
├── integration/         ← SAP / Simphony
└── security/            ← Config seguridad
```

### Controladores clave (rutas que verás en el navegador)

| Ruta | Controlador | Vista |
|---|---|---|
| `/portal` | `PortalDashboardController` | `dashboard.html` |
| `/portal/invoices` | `InvoicePortalController` | `invoices.html` |
| `/portal/facturacion/manual` | `InvoicePortalController` | `factura_manual.html` |
| `/portal/recepcion` → bandeja | `RecepcionPortalController` | `recepcion_bandeja.html` |
| `/portal/emision/reportes` | `ReportsPortalController` | reportes emisión |
| `/portal/admin/sociedades` | `AdminPortalController` | admin |

### Servicios importantes

| Clase | Rol |
|---|---|
| `InvoiceClientService` | Llama al API de `core-go` |
| `InvoiceOrchestratorService` | Emite + genera/guarda PDF |
| `PortalSessionService` | Sociedad / punto de venta en sesión |
| `ReceivedInvoiceRepository` | Consulta facturas recibidas en BD |

### Compilar sin Docker (opcional)

```powershell
cd microservice-portal-java
.\mvnw.cmd -q compile -DskipTests
```

---

## 8. Backend core (Go) — dónde tocar

### Ubicación

```
microservice-core-go/
└── cmd/api/main.go     ← API principal (rutas, emisión, listado)
```

### Headers multi-tenant (obligatorios en API)

```text
X-Tenant-ID: 00000000-0000-0000-0000-000000000001
X-Emission-Point-ID: 00000000-0000-0000-0000-000000000101
```

(IDs locales sembrados para pruebas.)

### Conceptos de negocio en BD

- Factura **EMITIDA**: tiene `emission_point_id`.
- Factura **RECIBIDA**: `emission_point_id` es `NULL`.
- El listado del core filtra por query `tipo=EMITIDA|RECIBIDA`.

Endpoints típicos (core en `:8081`):

- `GET /api/v1/invoices`
- `POST /api/v1/invoices`
- `GET /api/v1/invoices/{id}/documents/{kind}`

Más detalle de prueba: `examples/dian-flow/README.md`.

---

## 9. Base de datos

### Conexión local

```text
Host: localhost
Puerto: 5432
DB: zona_k_facturacion
User: zona_k_app
Password: zona_k_app_local_password
```

### Archivos

```
database/
├── schema.sql              ← Esquema inicial (se carga al crear el volumen)
└── migrations/             ← Scripts numerados (002, 003, …) aplicados por db-migrate
```

Al levantar compose, el servicio `db-migrate` aplica las migraciones en orden.

### Tablas que más verás

- `companies` / `sociedades` — tenants
- `emission_points` / puntos de venta — prefijo, resolución, consecutivos
- `invoices` — documentos
- `portal_users` (o equivalente auth) — login `admin`

---

## 10. Checklist de verificación sugerido (junior)

Usa esta lista al revisar el sistema:

### Arranque

- [ ] `docker compose ... up -d --build` termina sin contenedores en reinicio infinito.
- [ ] Puedes entrar a http://localhost:8080/login con `admin`/`admin`.
- [ ] El panel de módulos muestra Emisión, Recepción, Configuraciones, etc.

### Emisión

- [ ] `/portal/invoices` lista facturas del punto de venta seleccionado.
- [ ] El menú lateral solo muestra opciones de **emisión**.
- [ ] `/portal/facturacion/manual` abre el formulario (no redirige a la lista).
- [ ] Emitir un documento simple deja un registro nuevo / mensaje de éxito.
- [ ] Reportes de emisión abren y no rompen el layout.

### Recepción

- [ ] Entrar a Recepción lleva a `/portal/recepcion/bandeja`.
- [ ] El menú lateral es de recepción (no el de emisión).
- [ ] “Histórico recibidas” no abre el panel de facturas emitidas.

### Configuración

- [ ] Sociedades / Puntos de venta / Certificados cargan con menú de configuración.
- [ ] Desde el menú lateral no aparecen módulos ajenos.

### Navegación

- [ ] En cualquier módulo, “Panel de módulos” vuelve a `/portal`.
- [ ] No hay botones “ir a Facturas / Configuraciones / Emisión” mezclados en el top de cada página admin (deben usar el sidebar del módulo).

### Front + back al cambiar código

- [ ] Cambios hechos en `src/`, no solo en `target/`.
- [ ] Rebuild del contenedor afectado.
- [ ] Ctrl+F5 en el navegador.

---

## 11. Errores frecuentes y qué hacer

| Síntoma | Causa probable | Qué probar |
|---|---|---|
| Página en blanco / 500 | Error Thymeleaf (campo DTO faltante) | `docker logs zona-k-portal-java` |
| Manual o recepción te manda a emisión | Redirect viejo o enlace a `/portal/invoices` | Revisar controlador y `portal-nav.html` |
| Contenedor reiniciando | Fallo de arranque Spring/Go | Logs del contenedor; compilar local |
| No ves cambios de HTML | Estás editando `target/` o no rebuild | Editar `src/` + rebuild portal |
| Facturas vacías | Sociedad/PV incorrectos en sesión | Seleccionar sociedad y PV en el formulario de contexto |
| DIAN “falla” | Mock / DIAN_NET caído | `docker ps` y logs `zona-k-dian-net` |

---

## 12. Convenciones al hacer ajustes

1. **Un módulo = un menú.** No mezclar enlaces de otros módulos en el sidebar.
2. **Cambios mínimos.** Toca solo lo necesario para el bug/tarea.
3. **Front y back alineados.** Si agregas una ruta, agrega vista + `navModule`/`navActive`.
4. **No commits de secretos** (`.env`, passwords reales, certificados).
5. **Prueba en Docker local** antes de dar por cerrado.
6. Si no estás seguro del flujo DIAN real, trabaja con el **mock** (`DianConfig__Mock__Enabled=true` en local).

---

## 13. Mapa rápido “quiero cambiar X”

| Quiero… | Empieza aquí |
|---|---|
| Cambiar textos / layout de emisión | `templates/portal/invoices.html` |
| Cambiar menú lateral | `templates/portal/fragments/portal-nav.html` |
| Cambiar panel de módulos | `templates/portal/dashboard.html` |
| Agregar ruta web nueva | `controller/*PortalController.java` + template |
| Cambiar cómo se emite una factura | `InvoicePortalController` + `InvoiceOrchestratorService` + core-go |
| Cambiar listado API | `microservice-core-go/cmd/api/main.go` |
| Cambiar schema / seeds | `database/schema.sql` o nueva migración en `database/migrations/` |
| Ver correos de prueba | http://localhost:8025 |

---

## 14. Documentación adicional en el repo

- `examples/dian-flow/README.md` — pruebas del flujo DIAN / core.
- `DIAN_NET/DIAN_NET/README_API.md` — API del motor fiscal.
- `mocks/sap/README.md` — mock de integración SAP.
- `docker-compose.local.yml` — definición completa del entorno local.

---

## 15. Resumen para el primer día

1. Levanta Docker con `docker-compose.local.yml`.
2. Entra al portal con `admin` / `admin`.
3. Recorre Emisión → Manual → Reportes → Recepción → Configuración.
4. Abre en el editor:
   - `portal-nav.html` (front/nav),
   - `InvoicePortalController.java` y `RecepcionPortalController.java` (back portal),
   - `microservice-core-go/cmd/api/main.go` (back API).
5. Haz un cambio chico de texto en una plantilla, rebuild `portal-java`, valida con Ctrl+F5.
6. Anota dudas y bugs en una lista con: URL, pasos, esperado vs actual, y logs si aplica.

Con eso ya puedes revisar front y backend con contexto suficiente para proponer ajustes seguros.
