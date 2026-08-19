using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public interface IDianXmlDebugStore
    {
        XmlDebugSnapshot SaveBeforeSign(
            string documentKind,
            string ambiente,
            string identifier,
            string schemeName,
            string fileName,
            string originalXml,
            string xmlBeforeSign);

        void SaveSignedXml(string id, string signedXml);
        XmlDebugSnapshot? GetLatest();
        XmlDebugSnapshot? GetById(string id);
    }
}
