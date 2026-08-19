using DIAN_NET.Models;
using DIAN_NET.Services;
using Microsoft.AspNetCore.Mvc;

namespace DIAN_NET.Controllers
{
    [ApiController]
    [Route("api/v1/debug/xml")]
    public class DebugXmlController : ControllerBase
    {
        private readonly IDianXmlDebugStore _debugStore;

        public DebugXmlController(IDianXmlDebugStore debugStore)
        {
            _debugStore = debugStore;
        }

        [HttpGet("latest")]
        public ActionResult<object> GetLatest()
        {
            var snapshot = _debugStore.GetLatest();
            return snapshot == null ? NotFound() : Ok(ToMetadata(snapshot));
        }

        [HttpGet("latest/original")]
        public IActionResult GetLatestOriginalXml()
        {
            var snapshot = _debugStore.GetLatest();
            return snapshot == null ? NotFound() : Xml(snapshot.OriginalXml);
        }

        [HttpGet("latest/before-sign")]
        public IActionResult GetLatestXmlBeforeSign()
        {
            var snapshot = _debugStore.GetLatest();
            return snapshot == null ? NotFound() : Xml(snapshot.XmlBeforeSign);
        }

        [HttpGet("latest/signed")]
        public IActionResult GetLatestSignedXml()
        {
            var snapshot = _debugStore.GetLatest();
            return snapshot == null || string.IsNullOrWhiteSpace(snapshot.SignedXml)
                ? NotFound()
                : Xml(snapshot.SignedXml);
        }

        [HttpGet("{id}")]
        public ActionResult<object> GetById(string id)
        {
            var snapshot = _debugStore.GetById(id);
            return snapshot == null ? NotFound() : Ok(ToMetadata(snapshot));
        }

        [HttpGet("{id}/original")]
        public IActionResult GetOriginalXml(string id)
        {
            var snapshot = _debugStore.GetById(id);
            return snapshot == null ? NotFound() : Xml(snapshot.OriginalXml);
        }

        [HttpGet("{id}/before-sign")]
        public IActionResult GetXmlBeforeSign(string id)
        {
            var snapshot = _debugStore.GetById(id);
            return snapshot == null ? NotFound() : Xml(snapshot.XmlBeforeSign);
        }

        [HttpGet("{id}/signed")]
        public IActionResult GetSignedXml(string id)
        {
            var snapshot = _debugStore.GetById(id);
            return snapshot == null || string.IsNullOrWhiteSpace(snapshot.SignedXml)
                ? NotFound()
                : Xml(snapshot.SignedXml);
        }

        private static ContentResult Xml(string xml)
        {
            return new ContentResult
            {
                Content = xml,
                ContentType = "application/xml; charset=utf-8",
                StatusCode = 200
            };
        }

        private static object ToMetadata(XmlDebugSnapshot snapshot)
        {
            return new
            {
                snapshot.Id,
                snapshot.CreatedAt,
                snapshot.DocumentKind,
                snapshot.Ambiente,
                snapshot.Identifier,
                snapshot.SchemeName,
                snapshot.FileName,
                OriginalXmlLength = snapshot.OriginalXml.Length,
                XmlBeforeSignLength = snapshot.XmlBeforeSign.Length,
                SignedXmlAvailable = !string.IsNullOrWhiteSpace(snapshot.SignedXml)
            };
        }
    }
}
