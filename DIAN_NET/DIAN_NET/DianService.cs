using System;
using System.IO;
using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.ServiceModel;
using System.ServiceModel.Channels;
using System.ServiceModel.Security;
using System.ServiceModel.Security.Tokens;
using DIAN_NET.DIANreference;


namespace DIAN_NET
{
    public class DianService : IDisposable
    {
        private WcfDianCustomerServicesClient? _client;
        private readonly string _certificatePath;
        private readonly string _certificatePassword;
        private readonly string _serviceUrl;

        public DianService(string serviceUrl, string certificatePath, string certificatePassword)
        {
            _serviceUrl = serviceUrl ?? throw new ArgumentNullException(nameof(serviceUrl));
            _certificatePath = certificatePath ?? throw new ArgumentNullException(nameof(certificatePath));
            _certificatePassword = certificatePassword ?? throw new ArgumentNullException(nameof(certificatePassword));

            InitializeClient();
        }

        private Binding CreateBinding()
        {
            // Usamos el Binding estándar para comunicación segura con la DIAN
            var binding = new CustomBinding();

            // 1. Elemento de seguridad (Aquí es donde fallaba)
            // En lugar de construirlo manualmente, usamos una versión simplificada 
            // que .NET Framework entiende mejor para certificados
            var security = SecurityBindingElement.CreateCertificateOverTransportBindingElement();
            security.MessageSecurityVersion = MessageSecurityVersion.WSSecurity11WSTrust13WSSecureConversation13WSSecurityPolicy12BasicSecurityProfile10;
            security.IncludeTimestamp = true;
            security.LocalClientSettings.TimestampValidityDuration = TimeSpan.FromMinutes(5);

            // 2. Codificación del mensaje (Soap 1.2)
            var encoding = new TextMessageEncodingBindingElement
            {
                MessageVersion = MessageVersion.Soap12WSAddressing10
            };

            // 3. Transporte seguro (HTTPS)
            var transport = new HttpsTransportBindingElement
            {
                RequireClientCertificate = true,
                MaxReceivedMessageSize = 20000000, // 20MB para respuestas largas
                MaxBufferSize = 20000000
            };

            binding.Elements.Add(security);
            binding.Elements.Add(encoding);
            binding.Elements.Add(transport);

            return binding;
        }

        private void InitializeClient()
        {
            // Configurar TLS 1.2
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            // Cargar el certificado
            if (!File.Exists(_certificatePath))
            {
                throw new FileNotFoundException($"No se encontró el certificado en la ruta: {_certificatePath}");
            }

            X509Certificate2 certificate = new X509Certificate2(
                _certificatePath,
                _certificatePassword,
                X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet);

            // Crear el binding usando el método helper
            var binding = CreateBinding();

            // Configurar el endpoint
            var endpointAddress = new EndpointAddress(new Uri(_serviceUrl));

            // Crear el cliente con el binding y endpoint
            _client = new WcfDianCustomerServicesClient(binding, endpointAddress);

            // Configurar las credenciales del cliente con el certificado
            _client.ClientCredentials.ClientCertificate.Certificate = certificate;

            // Configurar la validación del certificado del servidor
            _client.ClientCredentials.ServiceCertificate.Authentication.CertificateValidationMode = 
                X509CertificateValidationMode.None;
        }

        public DianResponse GetStatus(string trackId)
        {
            if (string.IsNullOrWhiteSpace(trackId))
            {
                throw new ArgumentException("El trackId no puede ser nulo o vacío", nameof(trackId));
            }

            try
            {
                if (_client == null)
                {
                    throw new InvalidOperationException("El cliente no ha sido inicializado");
                }
                return _client.GetStatus(trackId);
            }
            catch (Exception ex)
            {
                throw new Exception($"Error al obtener el estado del documento con trackId: {trackId}", ex);
            }
        }

        public NumberRangeResponseList GetNumberingRange(string nit, string softwareId)
        {
            if (string.IsNullOrWhiteSpace(nit))
            {
                throw new ArgumentException("El NIT no puede ser nulo o vacío", nameof(nit));
            }

            if (string.IsNullOrWhiteSpace(softwareId))
            {
                throw new ArgumentException("El softwareId no puede ser nulo o vacío", nameof(softwareId));
            }

            try
            {
                if (_client == null)
                {
                    throw new InvalidOperationException("El cliente no ha sido inicializado");
                }
                // El método GetNumberingRange requiere 3 parámetros: accountCode, accountCodeT, softwareCode
                // Mapeamos: nit -> accountCode, null -> accountCodeT, softwareId -> softwareCode
                return _client.GetNumberingRange(nit, null, softwareId);
            }
            catch (Exception ex)
            {
                throw new Exception($"Error al obtener el rango de numeración para NIT: {nit}, SoftwareId: {softwareId}", ex);
            }
        }

        public void Dispose()
        {
            if (_client != null)
            {
                try
                {
                    if (_client.State != CommunicationState.Faulted)
                    {
                        _client.Close();
                    }
                    else
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
}
