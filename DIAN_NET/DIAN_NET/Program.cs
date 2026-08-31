using DIAN_NET.Middleware;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace DIAN_NET
{
    public class Program
    {
        public static void Main(string[] args)
        {
            var builder = WebApplication.CreateBuilder(args);

            // Configuración
            builder.Configuration
                .SetBasePath(AppContext.BaseDirectory)
                .AddJsonFile("appsettings.json", optional: false, reloadOnChange: true)
                .AddEnvironmentVariables();

            // Agregar servicios al contenedor
            builder.Services.AddControllers(options =>
            {
                // Payloads desde Core Go pueden omitir colecciones opcionales; no tratarlas como required implícito.
                options.SuppressImplicitRequiredAttributeForNonNullableReferenceTypes = true;
            });
            builder.Services.AddEndpointsApiExplorer();
            builder.Services.AddSwaggerGen();

            // Configurar CORS si es necesario
            builder.Services.AddCors(options =>
            {
                options.AddPolicy("AllowAll", policy =>
                {
                    policy.AllowAnyOrigin()
                          .AllowAnyMethod()
                          .AllowAnyHeader();
                });
            });

            // Registrar servicios de DIAN
            var configuredCertificatePath = builder.Configuration["DianConfig:Certificado:RutaPfx"]
                ?? builder.Configuration["DianConfig:Certificado:Path"];
            var certificatePath = string.IsNullOrWhiteSpace(configuredCertificatePath)
                ? Path.Combine(AppContext.BaseDirectory, "Certificado.pfx")
                : configuredCertificatePath;
            // Mock:Enabled solo permite bootstrap de certificado local; el mock DIAN
            // se activa por request cuando la sociedad envía ambiente=Mock.
            var allowMockCertBootstrap = builder.Configuration.GetValue<bool>("DianConfig:Mock:Enabled");
            var certificatePassword = builder.Configuration["DianConfig:Certificado:Password"] ?? string.Empty;
            if (allowMockCertBootstrap && string.IsNullOrWhiteSpace(certificatePassword))
            {
                certificatePassword = "local-mock-certificate-password";
            }
            if (allowMockCertBootstrap && !File.Exists(certificatePath))
            {
                CreateLocalMockCertificate(certificatePath, certificatePassword);
            }

            // Servicios de transformación y firma
            builder.Services.AddSingleton<IXmlTransformService, XmlTransformService>();
            builder.Services.AddSingleton<ICufeQrService, CufeQrService>();
            builder.Services.AddSingleton<IXadesSignService, XadesSignService>();
            builder.Services.AddSingleton<IDianXmlDebugStore, DianXmlDebugStore>();
            builder.Services.AddScoped<EmissionRequestContext>();
            builder.Services.AddScoped<ITenantCertificateLoader>(provider =>
                new TenantCertificateLoader(
                    provider.GetRequiredService<EmissionRequestContext>(),
                    provider.GetRequiredService<IConfiguration>(),
                    certificatePath,
                    certificatePassword));

            // Mock vs DIAN real se decide por ambiente de la sociedad en cada request.
            builder.Services.AddSingleton<MockDianService>();
            builder.Services.AddScoped<IDianService>(provider =>
                new AmbienteRoutingDianService(
                    provider.GetRequiredService<MockDianService>(),
                    provider.GetRequiredService<ITenantCertificateLoader>()));

            // FacturacionService depende de ITenantCertificateLoader + EmissionRequestContext (scoped).
            // EmissionCertificateMiddleware debe ejecutarse antes de controllers para poblar headers S3/tenant.
            builder.Services.AddScoped<IFacturacionService>(provider =>
                new FacturacionService(
                    provider.GetRequiredService<IXmlTransformService>(),
                    provider.GetRequiredService<ICufeQrService>(),
                    provider.GetRequiredService<IXadesSignService>(),
                    provider.GetRequiredService<IDianService>(),
                    provider.GetRequiredService<IDianXmlDebugStore>(),
                    provider.GetRequiredService<ITenantCertificateLoader>()));
            builder.Services.AddScoped<IDianResolutionService, DianResolutionService>();
            builder.Services.AddScoped<IDianDocumentInfoService, DianDocumentInfoService>();
            builder.Services.AddScoped<IEmissionService, EmissionService>();
            builder.Services.AddScoped<IRadianEventService>(provider =>
            {
                var cufeQr = provider.GetRequiredService<ICufeQrService>();
                var xadesSign = provider.GetRequiredService<IXadesSignService>();
                var dianService = provider.GetRequiredService<IDianService>();
                var configuration = provider.GetRequiredService<IConfiguration>();
                return new RadianEventService(
                    cufeQr,
                    xadesSign,
                    dianService,
                    configuration,
                    certificatePath,
                    certificatePassword);
            });

            var app = builder.Build();

            // Configurar pipeline HTTP
            if (app.Environment.IsDevelopment())
            {
                app.UseSwagger();
                app.UseSwaggerUI();
            }

            app.UseHttpsRedirection();
            app.UseCors("AllowAll");
            app.UseMiddleware<EmissionCertificateMiddleware>();
            app.UseAuthorization();
            app.MapControllers();

            app.Run();
        }

        private static void CreateLocalMockCertificate(string certificatePath, string certificatePassword)
        {
            var directory = Path.GetDirectoryName(certificatePath);
            if (!string.IsNullOrWhiteSpace(directory))
            {
                Directory.CreateDirectory(directory);
            }

            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=Zona K DIAN Mock",
                rsa,
                HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);

            var certificate = request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddDays(-1),
                DateTimeOffset.UtcNow.AddYears(2));

            File.WriteAllBytes(
                certificatePath,
                certificate.Export(X509ContentType.Pfx, certificatePassword));
        }
    }
}
