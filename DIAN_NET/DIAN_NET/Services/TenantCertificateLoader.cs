using System.IO;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Amazon;
using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using Amazon.SecretsManager;
using Amazon.SecretsManager.Model;
using Microsoft.Extensions.Configuration;

namespace DIAN_NET.Services
{
    public sealed class TenantCertificateLoader : ITenantCertificateLoader, IDisposable
    {
        private static readonly X509KeyStorageFlags CertFlags =
            OperatingSystem.IsLinux() || OperatingSystem.IsMacOS()
                ? X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable
                : X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.Exportable;

        private readonly EmissionRequestContext _requestContext;
        private readonly IConfiguration _configuration;
        private readonly string _fallbackCertificatePath;
        private readonly string _fallbackCertificatePassword;
        private readonly IAmazonS3? _s3Client;
        private readonly IAmazonSecretsManager? _secretsClient;
        private readonly string? _invoiceDocumentsBucket;
        private X509Certificate2? _cachedCertificate;
        private string? _cachedCertificateKey;

        public TenantCertificateLoader(
            EmissionRequestContext requestContext,
            IConfiguration configuration,
            string fallbackCertificatePath,
            string fallbackCertificatePassword)
        {
            _requestContext = requestContext;
            _configuration = configuration;
            _fallbackCertificatePath = fallbackCertificatePath;
            _fallbackCertificatePassword = fallbackCertificatePassword;

            _invoiceDocumentsBucket = FirstNonEmpty(
                _configuration["Aws:S3:InvoiceDocumentsBucket"],
                Environment.GetEnvironmentVariable("AWS_S3_INVOICE_DOCUMENTS_BUCKET"),
                "invoice-documents-bucket");

            var awsOptions = BuildAwsClientConfig();
            if (awsOptions != null)
            {
                _s3Client = new AmazonS3Client(awsOptions.Credentials, awsOptions.S3Config);
                _secretsClient = new AmazonSecretsManagerClient(awsOptions.Credentials, awsOptions.SecretsConfig);
            }
        }

        public X509Certificate2 LoadCertificate(string ambiente)
        {
            var cacheKey = BuildCacheKey(ambiente);
            if (_cachedCertificate != null && string.Equals(_cachedCertificateKey, cacheKey, StringComparison.Ordinal))
            {
                return _cachedCertificate;
            }

            if (_cachedCertificate != null)
            {
                _cachedCertificate.Dispose();
                _cachedCertificate = null;
                _cachedCertificateKey = null;
            }

            _cachedCertificate = ResolveCertificate(ambiente);
            _cachedCertificateKey = cacheKey;
            return _cachedCertificate;
        }

        private X509Certificate2 ResolveCertificate(string ambiente)
        {
            if (!string.IsNullOrWhiteSpace(_requestContext.CertificatePfxBase64))
            {
                return LoadFromBase64(
                    _requestContext.CertificatePfxBase64,
                    _requestContext.CertificatePassword ?? string.Empty);
            }

            if (!string.IsNullOrWhiteSpace(_requestContext.CertS3Key)
                && !string.IsNullOrWhiteSpace(_requestContext.PasswordSecretKey))
            {
                return LoadFromS3AndSecret(
                    _requestContext.CertS3Key.Trim(),
                    _requestContext.PasswordSecretKey.Trim());
            }

            if (AmbienteRoutingDianService.IsMock(ambiente))
            {
                return LoadMockSigningCertificate();
            }

            var tenantHint = string.IsNullOrWhiteSpace(_requestContext.TenantId)
                ? "sociedad"
                : $"tenant_id={_requestContext.TenantId}";

            throw new InvalidOperationException(
                $"Ambiente {ambiente} requiere certificado digital de la sociedad ({tenantHint}). "
                + "Envíe headers X-Cert-S3-Key y X-Cert-Password-Secret-Key, "
                + "o certificate_pfx_base64 en el body.");
        }

        private string BuildCacheKey(string ambiente)
        {
            var normalizedAmbiente = ambiente?.Trim().ToLowerInvariant() ?? string.Empty;
            var pfxMarker = string.IsNullOrWhiteSpace(_requestContext.CertificatePfxBase64)
                ? string.Empty
                : $"{_requestContext.CertificatePfxBase64.Length}:{_requestContext.CertificatePfxBase64.GetHashCode(StringComparison.Ordinal)}";
            return string.Join('|',
                normalizedAmbiente,
                _requestContext.TenantId ?? string.Empty,
                _requestContext.CertS3Key ?? string.Empty,
                _requestContext.PasswordSecretKey ?? string.Empty,
                pfxMarker,
                _requestContext.CertificatePassword ?? string.Empty);
        }

        private X509Certificate2 LoadMockSigningCertificate()
        {
            try
            {
                return LoadFallbackCertificate();
            }
            catch (InvalidOperationException ex)
            {
                throw new InvalidOperationException(
                    "Ambiente Mock requiere certificado local para firmar el XML. "
                    + "Configure DianConfig:Mock:Enabled=true o provea certificado de sociedad "
                    + "(headers X-Cert-S3-Key / body certificate_pfx_base64).",
                    ex);
            }
        }

        private X509Certificate2 LoadFromS3AndSecret(string objectKey, string secretName)
        {
            if (_s3Client == null || _secretsClient == null)
            {
                throw new InvalidOperationException(
                    "Cliente AWS no configurado en DIAN_NET. Revise Aws:Region y credenciales.");
            }

            byte[] pfxBytes;
            try
            {
                using var response = _s3Client.GetObjectAsync(new GetObjectRequest
                {
                    BucketName = _invoiceDocumentsBucket,
                    Key = objectKey
                }).GetAwaiter().GetResult();

                using var memory = new MemoryStream();
                response.ResponseStream.CopyTo(memory);
                pfxBytes = memory.ToArray();
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException(
                    $"No fue posible descargar certificado s3://{_invoiceDocumentsBucket}/{objectKey}: {ex.Message}", ex);
            }

            if (pfxBytes.Length == 0)
            {
                throw new InvalidOperationException(
                    $"Certificado vacío en s3://{_invoiceDocumentsBucket}/{objectKey}");
            }

            var password = ResolveSecretPassword(secretName);
            return LoadFromBytes(pfxBytes, password);
        }

        private string ResolveSecretPassword(string secretName)
        {
            try
            {
                var response = _secretsClient!.GetSecretValueAsync(new GetSecretValueRequest
                {
                    SecretId = secretName
                }).GetAwaiter().GetResult();

                var secretString = response.SecretString;
                if (string.IsNullOrWhiteSpace(secretString))
                {
                    throw new InvalidOperationException($"Secreto {secretName} vacío.");
                }

                return ParsePasswordFromSecretJson(secretString, secretName);
            }
            catch (Exception ex) when (ex is not InvalidOperationException)
            {
                throw new InvalidOperationException(
                    $"No fue posible leer contraseña del certificado en Secrets Manager ({secretName}): {ex.Message}", ex);
            }
        }

        internal static string ParsePasswordFromSecretJson(string secretString, string secretName)
        {
            try
            {
                using var doc = JsonDocument.Parse(secretString);
                var root = doc.RootElement;

                if (root.TryGetProperty("certificates", out var certificates)
                    && certificates.TryGetProperty("default", out var defaultCert)
                    && defaultCert.TryGetProperty("p12_password", out var passwordNode))
                {
                    var password = passwordNode.GetString();
                    if (!string.IsNullOrWhiteSpace(password))
                    {
                        return password;
                    }
                }

                if (root.TryGetProperty("p12_password", out var flatPassword))
                {
                    var password = flatPassword.GetString();
                    if (!string.IsNullOrWhiteSpace(password))
                    {
                        return password;
                    }
                }
            }
            catch (JsonException ex)
            {
                throw new InvalidOperationException(
                    $"JSON inválido en secreto {secretName}: {ex.Message}", ex);
            }

            throw new InvalidOperationException(
                $"Secreto {secretName} no contiene certificates.default.p12_password.");
        }

        private static X509Certificate2 LoadFromBase64(string base64, string password)
        {
            try
            {
                var bytes = Convert.FromBase64String(base64.Trim());
                return LoadFromBytes(bytes, password);
            }
            catch (Exception ex) when (ex is not InvalidOperationException)
            {
                throw new InvalidOperationException(
                    "No fue posible cargar el certificado PFX/P12 de la sociedad: " + ex.Message, ex);
            }
        }

        private static X509Certificate2 LoadFromBytes(byte[] bytes, string password)
        {
            try
            {
                return new X509Certificate2(bytes, password ?? string.Empty, CertFlags);
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException(
                    "Contraseña incorrecta o certificado PFX/P12 inválido para la sociedad: " + ex.Message, ex);
            }
        }

        private X509Certificate2 LoadFallbackCertificate()
        {
            if (string.IsNullOrWhiteSpace(_fallbackCertificatePath)
                || !File.Exists(_fallbackCertificatePath))
            {
                throw new InvalidOperationException(
                    "Certificado fallback no disponible. Configure certificado de sociedad o habilite mock local.");
            }

            return new X509Certificate2(
                _fallbackCertificatePath,
                _fallbackCertificatePassword,
                CertFlags);
        }

        private AwsClientOptions? BuildAwsClientConfig()
        {
            var regionName = FirstNonEmpty(
                _configuration["Aws:Region"],
                Environment.GetEnvironmentVariable("AWS_REGION"),
                "us-east-1");

            var endpoint = FirstNonEmpty(
                _configuration["Aws:EndpointUrl"],
                Environment.GetEnvironmentVariable("AWS_ENDPOINT_URL"),
                Environment.GetEnvironmentVariable("AWS_S3_ENDPOINT_OVERRIDE"));

            var accessKey = FirstNonEmpty(
                _configuration["Aws:AccessKeyId"],
                Environment.GetEnvironmentVariable("AWS_ACCESS_KEY_ID"));

            var secretKey = FirstNonEmpty(
                _configuration["Aws:SecretAccessKey"],
                Environment.GetEnvironmentVariable("AWS_SECRET_ACCESS_KEY"));

            AWSCredentials credentials;
            if (!string.IsNullOrWhiteSpace(accessKey) && !string.IsNullOrWhiteSpace(secretKey))
            {
                credentials = new BasicAWSCredentials(accessKey, secretKey);
            }
            else
            {
                credentials = FallbackCredentialsFactory.GetCredentials();
            }

            var s3Config = new AmazonS3Config
            {
                RegionEndpoint = RegionEndpoint.GetBySystemName(regionName)
            };

            var secretsConfig = new AmazonSecretsManagerConfig
            {
                RegionEndpoint = RegionEndpoint.GetBySystemName(regionName)
            };

            if (!string.IsNullOrWhiteSpace(endpoint))
            {
                s3Config.ServiceURL = endpoint;
                s3Config.ForcePathStyle = true;
                s3Config.UseHttp = endpoint.StartsWith("http://", StringComparison.OrdinalIgnoreCase);

                secretsConfig.ServiceURL = endpoint;
            }

            return new AwsClientOptions(credentials, s3Config, secretsConfig);
        }

        private static string? FirstNonEmpty(params string?[] values)
        {
            foreach (var value in values)
            {
                if (!string.IsNullOrWhiteSpace(value))
                {
                    return value.Trim();
                }
            }

            return null;
        }

        private sealed record AwsClientOptions(
            AWSCredentials Credentials,
            AmazonS3Config S3Config,
            AmazonSecretsManagerConfig SecretsConfig);

        public void Dispose()
        {
            _cachedCertificate?.Dispose();
            _cachedCertificate = null;
            _cachedCertificateKey = null;
        }
    }
}
