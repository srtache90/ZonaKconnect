using System;
using System.Collections.Concurrent;
using DIAN_NET.Models;

namespace DIAN_NET.Services
{
    public class DianXmlDebugStore : IDianXmlDebugStore
    {
        private const int MaxSnapshots = 25;
        private readonly ConcurrentDictionary<string, XmlDebugSnapshot> _snapshots = new();
        private readonly ConcurrentQueue<string> _order = new();
        private string? _latestId;

        public XmlDebugSnapshot SaveBeforeSign(
            string documentKind,
            string ambiente,
            string identifier,
            string schemeName,
            string fileName,
            string originalXml,
            string xmlBeforeSign)
        {
            var snapshot = new XmlDebugSnapshot
            {
                Id = Guid.NewGuid().ToString("N"),
                CreatedAt = DateTimeOffset.UtcNow,
                DocumentKind = documentKind,
                Ambiente = ambiente,
                Identifier = identifier,
                SchemeName = schemeName,
                FileName = fileName,
                OriginalXml = originalXml,
                XmlBeforeSign = xmlBeforeSign
            };

            _snapshots[snapshot.Id] = snapshot;
            _order.Enqueue(snapshot.Id);
            _latestId = snapshot.Id;
            TrimOldSnapshots();
            return snapshot;
        }

        public void SaveSignedXml(string id, string signedXml)
        {
            if (_snapshots.TryGetValue(id, out var snapshot))
            {
                snapshot.SignedXml = signedXml;
            }
        }

        public XmlDebugSnapshot? GetLatest()
        {
            return _latestId != null && _snapshots.TryGetValue(_latestId, out var snapshot)
                ? snapshot
                : null;
        }

        public XmlDebugSnapshot? GetById(string id)
        {
            return _snapshots.TryGetValue(id, out var snapshot) ? snapshot : null;
        }

        private void TrimOldSnapshots()
        {
            while (_snapshots.Count > MaxSnapshots && _order.TryDequeue(out var oldId))
            {
                if (oldId != _latestId)
                {
                    _snapshots.TryRemove(oldId, out _);
                }
            }
        }
    }
}
