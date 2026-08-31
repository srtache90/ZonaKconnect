using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.ServiceModel;
using System.ServiceModel.Channels;
using System.ServiceModel.Description;
using System.ServiceModel.Dispatcher;
using System.ServiceModel.Security;
using System.ServiceModel.Security.Tokens;
using System.Runtime.Serialization;
using System.Xml;
using System.Text.RegularExpressions;
using DIAN_NET.DIANreference;

using DIAN_NET.Services;
using DIAN_NET.Models;

namespace DIAN_NET
{
    public class DianManager : IDianService, IDisposable
    {
        private readonly string _certificatePath;
        private readonly string _certificatePassword;
        private readonly X509Certificate2? _certificate;
        private string? _currentAmbiente;
        private string? _defaultAmbiente;
        private WcfDianCustomerServicesClient? _client;

        public DianManager(string serviceUrl, string certificatePath, string certificatePassword)
        {
            // serviceUrl se mantiene para compatibilidad pero ahora se determina desde el ambiente
            _certificatePath = certificatePath ?? throw new ArgumentNullException(nameof(certificatePath));
            _certificatePassword = certificatePassword ?? throw new ArgumentNullException(nameof(certificatePassword));
            _certificate = null;

            // Usamos serviceUrl como "fuente" del ambiente solo para consultas sin parámetro ambiente.
            if (!string.IsNullOrWhiteSpace(serviceUrl))
            {
                if (serviceUrl.Contains("vpfe-hab", StringComparison.OrdinalIgnoreCase))
                {
                    _defaultAmbiente = "Habilitacion";
                }
                else if (serviceUrl.Contains("vpfe.dian.gov.co", StringComparison.OrdinalIgnoreCase))
                {
                    _defaultAmbiente = "Produccion";
                }
            }
        }

        // Constructor adicional para inyección de dependencias (serviceUrl se ignora)
        public DianManager(string certificatePath, string certificatePassword)
            : this("", certificatePath, certificatePassword)
        {
        }

        /// <summary>Usa un certificado en memoria (p. ej. por sociedad / tenant).</summary>
        public DianManager(X509Certificate2 certificate)
        {
            _certificate = certificate ?? throw new ArgumentNullException(nameof(certificate));
            _certificatePath = string.Empty;
            _certificatePassword = string.Empty;
        }

        private X509Certificate2 ResolveCertificate()
        {
            if (_certificate != null)
            {
                return _certificate;
            }

            if (!File.Exists(_certificatePath))
            {
                throw new FileNotFoundException($"No se encontró el certificado en la ruta: {_certificatePath}");
            }

            return new X509Certificate2(
                _certificatePath,
                _certificatePassword,
                X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable);
        }

        private Binding CreateBinding()
        {
            // SecurityMode solicitado: TransportWithMessageCredential (transport HTTPS + credencial en mensaje).
            _ = new WSHttpBinding(SecurityMode.TransportWithMessageCredential);

            // Usamos CustomBinding para poder fijar MessageSecurityVersion explícitamente.
            var security = SecurityBindingElement.CreateCertificateOverTransportBindingElement();
            security.MessageSecurityVersion = MessageSecurityVersion.WSSecurity11WSTrustFebruary2005WSSecureConversationFebruary2005WSSecurityPolicy11BasicSecurityProfile10;
            security.IncludeTimestamp = true;
            security.DefaultAlgorithmSuite = SecurityAlgorithmSuite.Basic256Sha256;
            security.SecurityHeaderLayout = SecurityHeaderLayout.Lax;
            security.LocalClientSettings.MaxClockSkew = TimeSpan.FromMinutes(5);
            security.LocalClientSettings.TimestampValidityDuration = TimeSpan.FromMinutes(5);

            var textEncoding = new TextMessageEncodingBindingElement
            {
                MessageVersion = MessageVersion.Soap12WSAddressing10
            };

            var httpsTransport = new HttpsTransportBindingElement
            {
                RequireClientCertificate = true,
                MaxReceivedMessageSize = 20000000,
                MaxBufferSize = 20000000
            };

            return new CustomBinding(security, textEncoding, httpsTransport);
        }

        private WcfDianCustomerServicesClient GetClient(string ambiente)
        {
            // Determinar la URL según el ambiente
            string serviceUrl;
            switch (ambiente?.ToLower())
            {
                case "habilitacion":
                case "habilitación":
                    serviceUrl = "https://vpfe-hab.dian.gov.co/WcfDianCustomerServices.svc";
                    break;
                case "produccion":
                case "producción":
                    serviceUrl = "https://vpfe.dian.gov.co/WcfDianCustomerServices.svc";
                    break;
                default:
                    throw new ArgumentException($"Ambiente '{ambiente}' no es válido. Use 'Habilitacion' o 'Produccion'.", nameof(ambiente));
            }

            // Si el cliente existe pero el ambiente cambió, cerrarlo y crear uno nuevo
            if (_client != null && _currentAmbiente != ambiente)
            {
                try
                {
                    if (_client.State == CommunicationState.Opened || _client.State == CommunicationState.Created)
                    {
                        _client.Close();
                    }
                    else if (_client.State == CommunicationState.Faulted)
                    {
                        _client.Abort();
                    }
                }
                catch
                {
                    _client.Abort();
                }
                finally
                {
                    _client = null;
                }
            }

            if (_client == null || _client.State != CommunicationState.Opened)
            {
                ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

                var certificate = ResolveCertificate();

                var binding = CreateBinding();
                var endpointAddress = new EndpointAddress(new Uri(serviceUrl));

                _client = new WcfDianCustomerServicesClient(binding, endpointAddress);
                _client.ClientCredentials.ClientCertificate.Certificate = certificate;
                _client.ClientCredentials.ServiceCertificate.Authentication.CertificateValidationMode = 
                    X509CertificateValidationMode.None;

                // Evita que mustUnderstand de headers interfiera con la respuesta.
                var hasMustUnderstandBehavior = false;
                foreach (var behavior in _client.Endpoint.EndpointBehaviors)
                {
                    if (behavior is MustUnderstandBehavior)
                    {
                        hasMustUnderstandBehavior = true;
                        break;
                    }
                }

                if (!hasMustUnderstandBehavior)
                {
                    _client.Endpoint.EndpointBehaviors.Add(new MustUnderstandBehavior(false));
                }

                if (_client.State != CommunicationState.Opened)
                {
                    _client.Open();
                }
                
                _currentAmbiente = ambiente;
            }

            return _client;
        }

        public DianResponse ConsultarEstado(string trackId, string ambiente)
        {
            if (string.IsNullOrWhiteSpace(trackId))
            {
                throw new ArgumentException("El trackId no puede ser nulo o vacío", nameof(trackId));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            var client = GetClient(ambiente);
            return client.GetStatus(trackId);
        }

        public NumberRangeResponseList ConsultarRangos(string nit, string idSoftware, string ambiente)
        {
            if (string.IsNullOrWhiteSpace(nit))
            {
                throw new ArgumentException("El NIT no puede ser nulo o vacío", nameof(nit));
            }

            if (string.IsNullOrWhiteSpace(idSoftware))
            {
                throw new ArgumentException("El idSoftware no puede ser nulo o vacío", nameof(idSoftware));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            var client = GetClient(ambiente);
            
            // Debug: Verificar valores antes de enviar
            Debug.WriteLine("=== DEBUG: Valores antes de GetNumberingRange ===");
            Debug.WriteLine($"NIT (accountCode): '{nit}' | Longitud: {nit.Length} | Tiene espacios: {nit.Contains(' ')}");
            Debug.WriteLine($"NIT (accountCodeT): '{nit}' | Longitud: {nit.Length} | Tiene espacios: {nit.Contains(' ')}");
            Debug.WriteLine($"NIT (bytes): [{string.Join(", ", System.Text.Encoding.UTF8.GetBytes(nit))}]");
            Debug.WriteLine($"SoftwareCode (idSoftware): '{idSoftware}' | Longitud: {idSoftware.Length} | Tiene espacios: {idSoftware.Contains(' ')}");
            Debug.WriteLine($"SoftwareCode (bytes): [{string.Join(", ", System.Text.Encoding.UTF8.GetBytes(idSoftware))}]");
            Debug.WriteLine("================================================");
            
            // Pasando el NIT dos veces: accountCode y accountCodeT (para contribuyentes individuales)
            var respuesta = client.GetNumberingRange(nit, nit, idSoftware);
            return respuesta;
        }

        public ConsultarEmpresaDIANResponse ConsultarEmpresaDIAN(string nit)
        {
            if (string.IsNullOrWhiteSpace(nit))
            {
                throw new ArgumentException("El NIT no puede ser nulo o vacío", nameof(nit));
            }

            // Limpieza del NIT antes de enviarlo a DIAN:
            // - Quita todo lo que no sea dígito
            // - Si viene con DV (ej: NIT 10 dígitos), quita el último dígito
            var nitEntrada = nit;
            string nitLimpio = Regex.Replace(nitEntrada, @"[^\d]", "");
            if (nitLimpio.Length > 9) { nitLimpio = nitLimpio.Substring(0, nitLimpio.Length - 1); }

            if (string.IsNullOrWhiteSpace(nitLimpio))
            {
                throw new ArgumentException("El NIT no es válido luego de limpiarlo (solo dígitos)", nameof(nit));
            }

            var ambiente = _defaultAmbiente ?? "Habilitacion";

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            // Intentamos primero NIT y si no devuelve éxito, probamos otros tipos de documento.
            // (Códigos según tabla DIAN: TCLA_NUM)
            var identificationTypes = new[]
            {
                "31", // NIT
                "12", // Tarjeta de Identidad
                "13", // Cédula de ciudadanía
                "21", // Tarjeta de extranjería
                "22", // Cédula de extranjería
                "41", // Pasaporte
                "42", // Extranjero
                "50", // NIT otro país
                "91", // NIUP
                "11"  // Registro civil
            };

            ConsultarEmpresaDIANResponse? lastResponse = null;

            foreach (var identificationType in identificationTypes)
            {
                try
                {
                    var client = GetClient(ambiente);
                    var respuesta = client.GetAcquirer(identificationType, nitLimpio.Trim());

                    // Imprimir lo que DIAN devuelve para depuración.
                    // Nota: en el contrato generado, "StatusDescription" se representa como "Message".
                    Console.WriteLine($"[GetAcquirer] identificationType={identificationType}, nit={nitLimpio}, StatusCode={respuesta?.StatusCode}, StatusDescription={respuesta?.Message}");
                    Debug.WriteLine($"[GetAcquirer] identificationType={identificationType}, nit={nitLimpio}, StatusCode={respuesta?.StatusCode}, StatusDescription={respuesta?.Message}");

                    var mapped = new ConsultarEmpresaDIANResponse
                    {
                        Email = respuesta?.ReceiverEmail,
                        StatusCode = respuesta?.StatusCode ?? "ERROR",
                        // Para la UI:
                        // - si es éxito ("00"), usamos ReceiverName como Razón Social
                        // - si no es éxito, usamos Message como descripción del error
                        StatusDescription = string.Equals(respuesta?.StatusCode, "00", StringComparison.OrdinalIgnoreCase)
                            ? respuesta?.ReceiverName
                            : respuesta?.Message
                    };

                    // Si StatusDescription viene vacío, algunos registros antiguos requieren NIT con 10 dígitos:
                    // reintentar anteponiendo un '0' a la izquierda (si el NIT limpiado tiene 9 dígitos).
                    if (string.IsNullOrWhiteSpace(mapped.StatusDescription) && nitLimpio.Length == 9)
                    {
                        var nitConCero = "0" + nitLimpio;
                        var respuestaConCero = client.GetAcquirer(identificationType, nitConCero);

                        // Recalcular mapeo con el nuevo intento.
                        mapped = new ConsultarEmpresaDIANResponse
                        {
                            Email = respuestaConCero?.ReceiverEmail,
                            StatusCode = respuestaConCero?.StatusCode ?? "ERROR",
                            StatusDescription = string.Equals(respuestaConCero?.StatusCode, "00", StringComparison.OrdinalIgnoreCase)
                                ? respuestaConCero?.ReceiverName
                                : respuestaConCero?.Message
                        };

                        respuesta = respuestaConCero;

                        Console.WriteLine($"[GetAcquirer retry+0] identificationType={identificationType}, nit={nitConCero}, StatusCode={respuesta?.StatusCode}, StatusDescription={respuesta?.Message}");
                        Debug.WriteLine($"[GetAcquirer retry+0] identificationType={identificationType}, nit={nitConCero}, StatusCode={respuesta?.StatusCode}, StatusDescription={respuesta?.Message}");
                    }

                    // Antes de mostrar un error, imprimimos el XML exacto de AcquirerResponse.
                    if (!string.Equals(mapped.StatusCode, "00", StringComparison.OrdinalIgnoreCase))
                    {
                        Debug.WriteLine("=== DEBUG: AcquirerResponse XML (GetAcquirer) ===");
                        Debug.WriteLine(SerializeAdquirienteResponseToXml(respuesta));
                        Debug.WriteLine("=== END DEBUG: AcquirerResponse XML ===");
                    }

                    lastResponse = mapped;

                    if (string.Equals(mapped.StatusCode, "00", StringComparison.OrdinalIgnoreCase))
                    {
                        return mapped;
                    }

                    // Código "100": típicamente corresponde a "empresa encontrada pero sin correo técnico"
                    // (o variaciones). Para evitar que otros tipos sobrescriban este resultado, retornamos.
                    if (string.Equals(mapped.StatusCode, "100", StringComparison.OrdinalIgnoreCase))
                    {
                        return mapped;
                    }
                }
                catch (FaultException ex)
                {
                    var (code, description) = TryExtractHttpLikeStatus(ex.Message);

                    // Si falla autenticación/permiso, no tiene sentido reintentar con otro tipo.
                    if (string.Equals(code, "401", StringComparison.OrdinalIgnoreCase))
                    {
                        return new ConsultarEmpresaDIANResponse
                        {
                            Email = null,
                            StatusCode = code ?? "401",
                            StatusDescription = description ?? ex.Message
                        };
                    }

                    // Si es "no encontrado" (404), continuamos con el siguiente tipo.
                    if (string.Equals(code, "404", StringComparison.OrdinalIgnoreCase))
                    {
                        lastResponse = new ConsultarEmpresaDIANResponse
                        {
                            Email = null,
                            StatusCode = code,
                            StatusDescription = description ?? ex.Message
                        };
                        continue;
                    }

                    // Para otros códigos, mantenemos el último y continuamos intentando otros tipos.
                    lastResponse = new ConsultarEmpresaDIANResponse
                    {
                        Email = null,
                        StatusCode = code ?? "ERROR",
                        StatusDescription = description ?? ex.Message
                    };
                }
                catch (CommunicationException ex)
                {
                    var (code, description) = TryExtractHttpLikeStatus(ex.Message);

                    lastResponse = new ConsultarEmpresaDIANResponse
                    {
                        Email = null,
                        StatusCode = code ?? "COM",
                        StatusDescription = description ?? ex.Message
                    };
                }
            }

            return lastResponse ?? new ConsultarEmpresaDIANResponse
            {
                Email = null,
                StatusCode = "ERROR",
                StatusDescription = "No se pudo obtener información de la empresa en DIAN."
            };
        }

        private static (string? code, string? description) TryExtractHttpLikeStatus(string? message)
        {
            if (string.IsNullOrWhiteSpace(message))
            {
                return (null, null);
            }

            // DIAN puede responder con fallas que incluyen el código HTTP en el mensaje.
            if (message.Contains("401", StringComparison.OrdinalIgnoreCase))
                return ("401", "401 Unauthorized");

            if (message.Contains("404", StringComparison.OrdinalIgnoreCase))
                return ("404", "404 Not Found");

            return (null, null);
        }

        private static string SerializeAdquirienteResponseToXml(AdquirienteResponse? response)
        {
            if (response == null)
            {
                return "<AcquirerResponse>null</AcquirerResponse>";
            }

            // Serialización con DataContractSerializer para obtener un XML representativo del contrato devuelto por DIAN.
            // Esto es para depuración; el resultado exacto puede variar por namespaces, pero captura el contenido del objeto.
            var serializer = new DataContractSerializer(typeof(AdquirienteResponse));

            var settings = new XmlWriterSettings
            {
                Indent = false,
                OmitXmlDeclaration = true
            };

            using var sw = new StringWriter();
            using (var writer = XmlWriter.Create(sw, settings))
            {
                serializer.WriteObject(writer, response);
            }

            return sw.ToString();
        }

        public DianResponse EnviarFactura(byte[] zipData, string nombreArchivo, string ambiente)
        {
            if (zipData == null || zipData.Length == 0)
            {
                throw new ArgumentException("El archivo ZIP no puede ser nulo o vacío", nameof(zipData));
            }

            if (string.IsNullOrWhiteSpace(nombreArchivo))
            {
                throw new ArgumentException("El nombre del archivo no puede ser nulo o vacío", nameof(nombreArchivo));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            try
            {
                var client = GetClient(ambiente);
                
                Debug.WriteLine("=== DEBUG: Enviando factura ===");
                Debug.WriteLine($"Nombre archivo: '{nombreArchivo}'");
                Debug.WriteLine($"Tamaño ZIP: {zipData.Length} bytes");
                Debug.WriteLine($"Ambiente: {ambiente}");
                Debug.WriteLine("===============================");
                
                // Llamar al servicio SendBillSync
                var respuesta = client.SendBillSync(nombreArchivo, zipData);
                
                // Extraer información importante de la respuesta
                Debug.WriteLine("=== DEBUG: Respuesta DIAN ===");
                Debug.WriteLine($"StatusCode: {respuesta.StatusCode}");
                Debug.WriteLine($"StatusDescription: {respuesta.StatusDescription}");
                Debug.WriteLine($"IsValid: {respuesta.IsValid}");
                Debug.WriteLine($"XmlDocumentKey: {respuesta.XmlDocumentKey}");
                Debug.WriteLine($"XmlFileName: {respuesta.XmlFileName}");
                if (respuesta.XmlBytes != null && respuesta.XmlBytes.Length > 0)
                {
                    Debug.WriteLine($"XmlBytes (ApplicationResponse): {respuesta.XmlBytes.Length} bytes");
                }
                if (respuesta.ErrorMessage != null && respuesta.ErrorMessage.Length > 0)
                {
                    Debug.WriteLine($"Errores: {string.Join("; ", respuesta.ErrorMessage)}");
                }
                Debug.WriteLine("==============================");
                
                return respuesta;
            }
            catch (FaultException<DianResponse> ex)
            {
                // La DIAN suele devolver errores de validación como FaultException<DianResponse>
                var detalleInfo = "No disponible";
                if (ex.Detail != null)
                {
                    detalleInfo = $"StatusCode: {ex.Detail.StatusCode}, " +
                                $"StatusDescription: {ex.Detail.StatusDescription}, " +
                                $"IsValid: {ex.Detail.IsValid}, " +
                                $"StatusMessage: {ex.Detail.StatusMessage}";
                    
                    if (ex.Detail.ErrorMessage != null && ex.Detail.ErrorMessage.Length > 0)
                    {
                        detalleInfo += $", Errores: [{string.Join("; ", ex.Detail.ErrorMessage)}]";
                    }
                }
                
                var mensajeError = $"Error de validación de la DIAN (FaultException<DianResponse>):\r\n" +
                                 $"Código: {ex.Code?.Name}\r\n" +
                                 $"Mensaje: {ex.Message}\r\n" +
                                 $"Detalle: {detalleInfo}";
                
                Debug.WriteLine($"=== ERROR FaultException<DianResponse> ===");
                Debug.WriteLine(mensajeError);
                Debug.WriteLine($"StackTrace: {ex.StackTrace}");
                Debug.WriteLine("===========================================");
                
                throw new Exception(mensajeError, ex);
            }
            catch (FaultException ex)
            {
                // Captura FaultException genérica como fallback
                var mensajeError = $"Error de la DIAN (FaultException genérica):\r\n" +
                                 $"Código: {ex.Code?.Name}\r\n" +
                                 $"Mensaje: {ex.Message}\r\n" +
                                 $"Reason: {ex.Reason?.ToString()}";
                
                Debug.WriteLine($"=== ERROR FaultException (genérica) ===");
                Debug.WriteLine(mensajeError);
                Debug.WriteLine($"StackTrace: {ex.StackTrace}");
                Debug.WriteLine("========================================");
                
                throw new Exception(mensajeError, ex);
            }
            catch (CommunicationException ex)
            {
                var mensajeError = $"Error de comunicación con la DIAN:\r\n{ex.Message}";
                Debug.WriteLine($"=== ERROR CommunicationException ===");
                Debug.WriteLine(mensajeError);
                Debug.WriteLine($"StackTrace: {ex.StackTrace}");
                Debug.WriteLine("====================================");
                
                throw new Exception(mensajeError, ex);
            }
            catch (Exception ex)
            {
                var mensajeError = $"Error inesperado al enviar factura:\r\n{ex.Message}";
                Debug.WriteLine($"=== ERROR General ===");
                Debug.WriteLine(mensajeError);
                Debug.WriteLine($"StackTrace: {ex.StackTrace}");
                Debug.WriteLine("======================");
                
                throw new Exception(mensajeError, ex);
            }
        }

        public DianResponse EnviarNomina(byte[] zipData, string ambiente)
        {
            if (zipData == null || zipData.Length == 0)
            {
                throw new ArgumentException("El archivo ZIP de nómina no puede ser nulo o vacío", nameof(zipData));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            try
            {
                var client = GetClient(ambiente);
                return client.SendNominaSync(zipData);
            }
            catch (Exception ex)
            {
                throw new Exception($"Error al enviar nómina electrónica a la DIAN: {ex.Message}", ex);
            }
        }

        public DocumentInfoResponse ConsultarDocumentoInfo(string uuid, string ambiente)
        {
            if (string.IsNullOrWhiteSpace(uuid))
            {
                throw new ArgumentException("El UUID/CUFE no puede ser nulo o vacío", nameof(uuid));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            try
            {
                var client = GetClient(ambiente);
                Debug.WriteLine($"=== DEBUG: Consultando GetDocumentInfo uuid={uuid} ambiente={ambiente} ===");
                return client.GetDocumentInfo(uuid.Trim());
            }
            catch (Exception ex)
            {
                throw new Exception($"Error al consultar documento RADIAN en DIAN (CUFE/CUDE={uuid}): {ex.Message}", ex);
            }
        }

        public DianResponse EnviarEvento(byte[] zipData, string ambiente)
        {
            if (zipData == null || zipData.Length == 0)
            {
                throw new ArgumentException("El archivo ZIP del evento no puede ser nulo o vacío", nameof(zipData));
            }

            if (string.IsNullOrWhiteSpace(ambiente))
            {
                throw new ArgumentException("El ambiente no puede ser nulo o vacío", nameof(ambiente));
            }

            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            try
            {
                var client = GetClient(ambiente);
                Debug.WriteLine($"=== DEBUG: Enviando evento RADIAN ambiente={ambiente} bytes={zipData.Length} ===");
                return client.SendEventUpdateStatus(zipData);
            }
            catch (FaultException<DianResponse> ex)
            {
                var detalle = ex.Detail == null
                    ? "sin detalle"
                    : $"{ex.Detail.StatusCode} {ex.Detail.StatusDescription} [{string.Join("; ", ex.Detail.ErrorMessage ?? Array.Empty<string>())}]";
                throw new Exception($"DIAN rechazó el evento RADIAN: {detalle}", ex);
            }
            catch (Exception ex)
            {
                throw new Exception($"Error al enviar evento RADIAN a la DIAN: {ex.Message}", ex);
            }
        }

        public void Dispose()
        {
            if (_client != null)
            {
                try
                {
                    if (_client.State == CommunicationState.Opened || _client.State == CommunicationState.Created)
                    {
                        _client.Close();
                    }
                    else if (_client.State == CommunicationState.Faulted)
                    {
                        _client.Abort();
                    }
                }
                catch
                {
                    _client.Abort();
                }
                finally
                {
                    _client = null;
                }
            }
        }
    }

    public class MustUnderstandBehavior : IEndpointBehavior, IClientMessageInspector
    {
        private readonly bool _mustUnderstand;

        public MustUnderstandBehavior(bool mustUnderstand)
        {
            _mustUnderstand = mustUnderstand;
        }

        public void AddBindingParameters(ServiceEndpoint endpoint, BindingParameterCollection bindingParameters)
        {
        }

        public void ApplyClientBehavior(ServiceEndpoint endpoint, ClientRuntime clientRuntime)
        {
            clientRuntime.ClientMessageInspectors.Add(this);
        }

        public void ApplyDispatchBehavior(ServiceEndpoint endpoint, EndpointDispatcher endpointDispatcher)
        {
        }

        public void Validate(ServiceEndpoint endpoint)
        {
        }

        public object BeforeSendRequest(ref Message request, IClientChannel channel)
        {
            // Hook reservado para trazas o ajustes de header si se requieren después.
            _ = _mustUnderstand;
            return null!;
        }

        public void AfterReceiveReply(ref Message reply, object correlationState)
        {
            // No-op: el objetivo es registrar el behavior y evitar bloqueos por headers estrictos.
        }
    }
}
