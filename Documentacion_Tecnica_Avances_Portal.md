# Documentación técnica de avances
## Evolución del portal, recepción de facturas y administración

**Periodo documentado:** aproximadamente los últimos dos meses  
**Proyecto:** Zona K e-Docs

## 1. Propósito

Este documento resume los cambios realizados durante el periodo de trabajo en el portal. Incluye tanto las mejoras visuales aplicadas a distintas ventanas del sistema como los cambios funcionales y de base de datos relacionados con roles, permisos, alcance por punto de venta y recepción de facturas.

La documentación distingue los avances previos de la funcionalidad de administración de reglas de proveedor a punto de venta, que complementa el trabajo realizado en el flujo de recepción.

## 2. Mejoras visuales y de experiencia de usuario

Durante el periodo se realizaron ajustes de estilos en distintas páginas y ventanas del portal; el trabajo visual no se limitó a la pantalla de proveedor a punto de venta. El propósito fue dar mayor coherencia a la interfaz y facilitar el uso de los módulos en las tareas cotidianas.

Entre los aspectos trabajados se encuentran:

- organización y presentación de las secciones de las páginas;
- uniformidad visual de formularios, botones, tarjetas, tablas y mensajes;
- jerarquía más clara entre títulos, subtítulos, contenido y acciones;
- ajustes de espaciado, alineación y distribución de los elementos;
- mayor legibilidad de la información y de los estados mostrados;
- navegación más consistente entre las secciones administrativas.

En conjunto, estos ajustes buscan que las distintas ventanas del portal sean más claras, consistentes y fáciles de utilizar. La funcionalidad de cada página depende de su módulo; los cambios de estilo se enfocaron en la presentación y usabilidad de la interfaz.

## 3. Roles y control de acceso

Se definieron los roles utilizados en el portal:

- **ADMIN:** administración de las funciones administrativas.
- **EMISOR:** perfil asociado a las tareas de emisión.
- **RECEPTOR:** perfil asociado a las tareas de recepción.
- **CONSULTA:** acceso de consulta según los permisos configurados.

También se contempló la compatibilidad con el rol anterior **OPERADOR**, migrándolo a **EMISOR** en la migración de base de datos. Esto permite mantener la compatibilidad con los registros existentes mientras se utiliza la nomenclatura actual.

## 4. Alcance de usuarios por sociedad y punto de venta

Se incorporó una estructura para relacionar usuarios con sociedades y puntos de venta mediante la tabla `usuario_puntos_venta`.

La asignación permite representar dos alcances:

- cuando `emission_point_id` contiene un punto, el registro corresponde a ese punto de venta;
- cuando `emission_point_id` es `NULL`, el alcance representa todos los puntos de la sociedad.

La tabla incluye restricciones e índices para apoyar la integridad de las relaciones y las consultas por usuario, sociedad y punto de venta. Esta estructura permite delimitar el ámbito operativo asociado a cada usuario.

## 5. Asignación operativa de facturas recibidas

Se amplió la tabla `received_invoices` con campos para registrar datos de asignación operativa:

- `assigned_emission_point_id`: punto de venta asignado a la factura;
- `assignment_source`: origen o modalidad de la asignación;
- `assigned_at`: fecha y hora de la asignación;
- `assigned_by_user_id`: usuario que realizó la asignación, cuando corresponde.

Se establecieron los siguientes valores para `assignment_source`:

- **UNASSIGNED:** factura todavía sin punto asignado;
- **SUPPLIER_DEFAULT:** asignación basada en la regla configurada para el proveedor;
- **CENTRAL:** asignación identificada como central;
- **MANUAL:** asignación realizada manualmente;
- **MAILBOX:** asignación asociada al flujo de buzón.

La restricción de base de datos limita el campo a los valores definidos. Los campos permiten conservar información sobre el punto, el origen y los datos de auditoría disponibles para cada asignación.

## 6. Auditoría de reasignaciones

Se creó la tabla `received_invoice_assignment_audit` para conservar eventos de reasignación de facturas recibidas. Su estructura permite registrar la factura y sociedad, el punto de origen, el punto de destino, el usuario que ejecutó la acción, una razón opcional y la fecha del evento.

Esta información facilita el seguimiento posterior de los cambios de asignación y aporta trazabilidad al proceso operativo.

## 7. Automatización desarrollada: regla de proveedor a punto de venta

Se implementó en el portal una función administrativa para configurar la relación entre el NIT de un proveedor y un punto de venta dentro de una sociedad. Esta automatización fue desarrollada como parte del sistema.

La administración permite:

- seleccionar la sociedad;
- ingresar el NIT del proveedor;
- seleccionar el punto de venta asociado;
- agregar notas opcionales;
- consultar reglas activas e inactivas;
- desactivar una regla sin eliminar su registro.

La lógica de datos normaliza el NIT eliminando caracteres que no sean numéricos. Al guardar, crea la regla o actualiza la existente para la misma sociedad y NIT, y la deja activa. La consulta de reglas activas permite obtener el punto asociado al proveedor.

La tabla `supplier_default_points` conserva la sociedad, el NIT normalizado, el punto de venta, las notas, el estado y las fechas de creación y actualización. La restricción única por sociedad y NIT evita duplicar la regla para esa combinación.

La configuración de la regla proporciona la base para identificar el punto de venta correspondiente a un proveedor y utilizar ese resultado en el flujo de asignación de facturas.

## 8. Interfaz y componentes de la función de proveedor a punto

La pantalla administrativa de proveedor a punto incluye:

- selector de sociedad;
- formulario para crear o actualizar la regla;
- listado de reglas con proveedor, punto, notas y estado;
- acción para desactivar reglas activas;
- mensajes de confirmación o error.

El acceso a la pantalla se integró con la navegación administrativa y la sección de configuración del portal.

## 9. Componentes técnicos principales

### Aplicación

- `SupplierPointAdminController`: recibe las solicitudes para consultar, guardar y desactivar reglas; expone la pantalla únicamente a usuarios con rol ADMIN.
- `SupplierDefaultPointRepository`: consulta las reglas, normaliza el NIT, realiza la inserción o actualización y desactiva registros.
- `proveedores-punto.html`: presenta el formulario y la lista de reglas en la interfaz.

### Base de datos

La migración `018_usuarios_roles_puntos_asignacion_recepcion.sql` reúne cambios asociados a roles, alcance por punto de venta, reglas de proveedor, datos de asignación de facturas y auditoría.

## 10. Archivos y módulos trabajados

Los cambios del periodo abarcaron varias áreas del portal. La siguiente relación incluye ejemplos representativos del historial del proyecto; no se limita a la funcionalidad agregada más recientemente.

### Interfaz, estilos y navegación

- `microservice-portal-java/src/main/resources/templates/portal/dashboard.html`: ajustes de presentación y organización del dashboard.
- `microservice-portal-java/src/main/resources/static/css/portal-ui.css` y `microservice-portal-java/src/main/resources/static/css/zonak-portal.css`: estilos compartidos del portal.
- `microservice-portal-java/src/main/resources/templates/portal/fragments/portal-nav.html`: navegación y elementos compartidos entre ventanas.
- `microservice-portal-java/src/main/resources/templates/portal/admin/usuarios.html`, `sociedades.html`, `puntos-venta.html` y `certificados.html`: pantallas administrativas.
- `microservice-portal-java/src/main/resources/templates/portal/recepcion_bandeja.html`, `recepcion.html` y `recepcion/detalle.html`: ventanas del flujo de recepción.
- `microservice-portal-java/src/main/resources/templates/portal/factura_manual.html` e `invoices.html`: pantallas de facturación y consulta.
- `microservice-portal-java/src/main/resources/templates/portal/nomina/form.html`, `nomina/index.html` y `nomina/reportes.html`: ventanas de nómina.
- `microservice-portal-java/src/main/resources/templates/portal/documento-soporte/form.html`, `documento-soporte/index.html` y `documento-soporte/reportes.html`: ventanas de documentos soporte.
- `microservice-portal-java/src/main/resources/templates/portal/emision/reportes/` y `microservice-portal-java/src/main/resources/templates/portal/recepcion/reportes.html`: vistas de reportes.

Estos ejemplos dejan constancia de que los cambios visuales y de interfaz incluyeron el dashboard y otras ventanas del portal, además de la página de proveedor a punto de venta.

### Lógica de aplicación y automatización

- `microservice-portal-java/src/main/java/com/zonak/portal/controller/PortalDashboardController.java`
- `microservice-portal-java/src/main/java/com/zonak/portal/dashboard/PortalAnalyticsRepository.java`
- `microservice-portal-java/src/main/java/com/zonak/portal/dashboard/PortalDashboardExportService.java`
- `microservice-portal-java/src/test/java/com/zonak/portal/dashboard/PortalDashboardExportServiceTest.java`
- `microservice-portal-java/src/main/java/com/zonak/portal/admin/SupplierPointAdminController.java`
- `microservice-portal-java/src/main/java/com/zonak/portal/admin/SupplierDefaultPointRepository.java`

### Configuración y base de datos

- `microservice-portal-java/src/main/resources/templates/portal/configuraciones.html`
- `microservice-portal-java/src/main/resources/templates/portal/admin/proveedores-punto.html`
- `database/migrations/018_usuarios_roles_puntos_asignacion_recepcion.sql`

La migración indicada respalda los cambios de roles, alcance de usuario por punto de venta, asignación de facturas recibidas, reglas por proveedor y auditoría de reasignaciones.

## 11. Resultado

El trabajo realizado durante el periodo combina una mejora visual transversal del portal con cambios técnicos en permisos y recepción. Las mejoras de interfaz se aplicaron a distintas ventanas para hacerlas más consistentes y claras. En la parte funcional, se incorporaron estructuras para controlar el alcance por usuario, registrar asignaciones y auditar reasignaciones, además de una herramienta administrativa propia para gestionar reglas de asignación por proveedor.

En conjunto, estos cambios fortalecen la administración del portal y proporcionan una base más ordenada y trazable para el flujo de recepción de facturas.
