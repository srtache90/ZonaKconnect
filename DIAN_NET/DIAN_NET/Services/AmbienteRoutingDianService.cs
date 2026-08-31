using System;
using DIAN_NET.DIANreference;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    /// <summary>
    /// Enruta a mock o DIAN real según el ambiente del request (sociedad),
    /// no según un flag global del proceso.
    /// </summary>
    public sealed class AmbienteRoutingDianService : IDianService
    {
        private readonly MockDianService _mock;
        private readonly ITenantCertificateLoader _tenantCertificateLoader;

        public AmbienteRoutingDianService(
            MockDianService mock,
            ITenantCertificateLoader tenantCertificateLoader)
        {
            _mock = mock ?? throw new ArgumentNullException(nameof(mock));
            _tenantCertificateLoader = tenantCertificateLoader
                ?? throw new ArgumentNullException(nameof(tenantCertificateLoader));
        }

        public DianResponse ConsultarEstado(string trackId, string ambiente) =>
            IsMock(ambiente)
                ? _mock.ConsultarEstado(trackId, ambiente)
                : ExecuteReal(ambiente, manager => manager.ConsultarEstado(trackId, ambiente));

        public NumberRangeResponseList ConsultarRangos(string nit, string idSoftware, string ambiente) =>
            IsMock(ambiente)
                ? _mock.ConsultarRangos(nit, idSoftware, ambiente)
                : ExecuteReal(ambiente, manager => manager.ConsultarRangos(nit, idSoftware, ambiente));

        public ConsultarEmpresaDIANResponse ConsultarEmpresaDIAN(string nit) =>
            ExecuteReal("Habilitacion", manager => manager.ConsultarEmpresaDIAN(nit));

        public DianResponse EnviarFactura(byte[] zipData, string nombreArchivo, string ambiente) =>
            IsMock(ambiente)
                ? _mock.EnviarFactura(zipData, nombreArchivo, ambiente)
                : ExecuteReal(ambiente, manager => manager.EnviarFactura(zipData, nombreArchivo, ambiente));

        public DianResponse EnviarNomina(byte[] zipData, string ambiente) =>
            IsMock(ambiente)
                ? _mock.EnviarNomina(zipData, ambiente)
                : ExecuteReal(ambiente, manager => manager.EnviarNomina(zipData, ambiente));

        public DianResponse EnviarEvento(byte[] zipData, string ambiente) =>
            IsMock(ambiente)
                ? _mock.EnviarEvento(zipData, ambiente)
                : ExecuteReal(ambiente, manager => manager.EnviarEvento(zipData, ambiente));

        private T ExecuteReal<T>(string ambiente, Func<DianManager, T> action)
        {
            var (manager, ownsManager) = CreateRealManager(ambiente);
            try
            {
                return action(manager);
            }
            finally
            {
                if (ownsManager)
                {
                    manager.Dispose();
                }
            }
        }

        private (DianManager Manager, bool OwnsManager) CreateRealManager(string ambiente)
        {
            if (IsMock(ambiente))
            {
                throw new InvalidOperationException(
                    "CreateRealManager no debe invocarse con ambiente Mock; el enrutamiento DIAN debe usar MockDianService.");
            }

            var certificate = _tenantCertificateLoader.LoadCertificate(ambiente);
            return (new DianManager(certificate), true);
        }

        public static bool IsMock(string? ambiente) =>
            string.Equals(ambiente?.Trim(), "Mock", StringComparison.OrdinalIgnoreCase);
    }
}
