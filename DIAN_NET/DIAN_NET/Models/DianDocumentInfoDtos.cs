using System.Collections.Generic;

namespace DIAN_NET.Models
{
    public sealed class DianDocumentEventDto
    {
        public string Code { get; set; } = string.Empty;
        public string Label { get; set; } = string.Empty;
        public string Estado { get; set; } = "REGISTRADO";
        public string? EventUuid { get; set; }
    }

    public sealed class DianDocumentInfoQueryResponse
    {
        public string StatusCode { get; set; } = string.Empty;
        public string StatusDescription { get; set; } = string.Empty;
        public string? DocumentUuid { get; set; }
        public List<DianDocumentEventDto> Events { get; set; } = new();
    }
}
