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
            builder.Services.AddControllers();
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
            var configuredCertificatePath = builder.Configuration["DianConfig:Certificado:RutaPfx"];
            var certificatePath = string.IsNullOrWhiteSpace(configuredCertificatePath)
                ? Path.Combine(AppContext.BaseDirectory, "Certificado.pfx")
                : configuredCertificatePath;
            var dianMockEnabled = builder.Configuration.GetValue<bool>("DianConfig:Mock:Enabled");
            var certificatePassword = builder.Configuration["DianConfig:Certificado:Password"] ?? string.Empty;
            if (dianMockEnabled && string.IsNullOrWhiteSpace(certificatePassword))
            {
                certificatePassword = "local-mock-certificate-password";
            }
            if (dianMockEnabled && !File.Exists(certificatePath))
            {
                CreateLocalMockCertificate(certificatePath, certificatePassword);
            }

            // Servicios de transformación y firma
            builder.Services.AddSingleton<IXmlTransformService, XmlTransformService>();
            builder.Services.AddSingleton<ICufeQrService, CufeQrService>();
            builder.Services.AddSingleton<IXadesSignService, XadesSignService>();
            builder.Services.AddSingleton<IDianXmlDebugStore, DianXmlDebugStore>();

            // Servicio DIAN (scoped para manejar conexiones WCF correctamente)
            builder.Services.AddScoped<IDianService>(provider =>
            {
                if (dianMockEnabled)
                {
                    return new MockDianService();
                }

                // DianManager ahora tiene constructor que acepta solo certificado
                return new DianManager(certificatePath, certificatePassword);
            });

            // Servicio de orquestación
            builder.Services.AddScoped<IFacturacionService>(provider =>
            {
                var xmlTransform = provider.GetRequiredService<IXmlTransformService>();
                var cufeQr = provider.GetRequiredService<ICufeQrService>();
                var xadesSign = provider.GetRequiredService<IXadesSignService>();
                var dianService = provider.GetRequiredService<IDianService>();
                var xmlDebugStore = provider.GetRequiredService<IDianXmlDebugStore>();
                
                return new FacturacionService(
                    xmlTransform,
                    cufeQr,
                    xadesSign,
                    dianService,
                    xmlDebugStore,
                    certificatePath,
                    certificatePassword);
            });
            builder.Services.AddScoped<IEmissionService, EmissionService>();

            var app = builder.Build();

            // Configurar pipeline HTTP
            if (app.Environment.IsDevelopment())
            {
                app.UseSwagger();
                app.UseSwaggerUI();
            }

            app.UseHttpsRedirection();
            app.UseCors("AllowAll");
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
