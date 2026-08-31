using System.Collections.Generic;

namespace DIAN_NET.Models
{
    public sealed class DianNumberingRangeDto
    {
        public string ResolutionNumber { get; set; } = string.Empty;
        public string ResolutionDate { get; set; } = string.Empty;
        public string Prefix { get; set; } = string.Empty;
        public long FromNumber { get; set; }
        public long ToNumber { get; set; }
        public string ValidDateFrom { get; set; } = string.Empty;
        public string ValidDateTo { get; set; } = string.Empty;
        public string TechnicalKey { get; set; } = string.Empty;
    }

    public sealed class DianNumberingRangeQueryResponse
    {
        public string OperationCode { get; set; } = string.Empty;
        public string OperationDescription { get; set; } = string.Empty;
        public List<DianNumberingRangeDto> Resolutions { get; set; } = new();
    }
}
