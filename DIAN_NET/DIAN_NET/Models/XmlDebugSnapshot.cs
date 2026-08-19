using System;

namespace DIAN_NET.Models
{
    public class XmlDebugSnapshot
    {
        public string Id { get; set; } = string.Empty;
        public DateTimeOffset CreatedAt { get; set; }
        public string DocumentKind { get; set; } = string.Empty;
        public string Ambiente { get; set; } = string.Empty;
        public string Identifier { get; set; } = string.Empty;
        public string SchemeName { get; set; } = string.Empty;
        public string FileName { get; set; } = string.Empty;
        public string OriginalXml { get; set; } = string.Empty;
        public string XmlBeforeSign { get; set; } = string.Empty;
        public string? SignedXml { get; set; }
    }
}
