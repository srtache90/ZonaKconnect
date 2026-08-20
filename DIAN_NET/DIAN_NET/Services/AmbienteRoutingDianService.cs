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
        private readonly DianManager _real;

        public AmbienteRoutingDianService(MockDianService mock, DianManager real)
        {
            _mock = mock ?? throw new ArgumentNullException(nameof(mock));
            _real = real ?? throw new ArgumentNullException(nameof(real));
        }

        public DianResponse ConsultarEstado(string trackId, string ambiente) =>
            Resolve(ambiente).ConsultarEstado(trackId, ambiente);

        public NumberRangeResponseList ConsultarRangos(string nit, string idSoftware, string ambiente) =>
            Resolve(ambiente).ConsultarRangos(nit, idSoftware, ambiente);

        public ConsultarEmpresaDIANResponse ConsultarEmpresaDIAN(string nit) =>
            _real.ConsultarEmpresaDIAN(nit);

        public DianResponse EnviarFactura(byte[] zipData, string nombreArchivo, string ambiente) =>
            Resolve(ambiente).EnviarFactura(zipData, nombreArchivo, ambiente);

        public DianResponse EnviarNomina(byte[] zipData, string ambiente) =>
            Resolve(ambiente).EnviarNomina(zipData, ambiente);

        public DianResponse EnviarEvento(byte[] zipData, string ambiente) =>
            Resolve(ambiente).EnviarEvento(zipData, ambiente);

        private IDianService Resolve(string ambiente) =>
            IsMock(ambiente) ? _mock : _real;

        public static bool IsMock(string? ambiente) =>
            string.Equals(ambiente?.Trim(), "Mock", StringComparison.OrdinalIgnoreCase);
    }
}
