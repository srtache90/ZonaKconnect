package reception

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"io"
	"strings"
	"unicode/utf8"
)

type FiscalPackage struct {
	XMLs []string
	PDF  []byte
}

func ExtractFiscalPackage(content []byte, fileName string) FiscalPackage {
	pack := FiscalPackage{}
	name := strings.ToLower(strings.TrimSpace(fileName))
	if looksLikeZip(content) || strings.HasSuffix(name, ".zip") {
		collectXMLFromZip(content, &pack)
		return pack
	}
	if isPDF(content) {
		pack.PDF = content
		return pack
	}
	xml := decodeXML(content)
	if IsReceivableUBL(xml) {
		pack.XMLs = append(pack.XMLs, xml)
	}
	return pack
}

func collectXMLFromZip(content []byte, pack *FiscalPackage) {
	r, err := zip.NewReader(bytes.NewReader(content), int64(len(content)))
	if err != nil {
		return
	}
	for _, f := range r.File {
		rc, err := f.Open()
		if err != nil {
			continue
		}
		data, err := io.ReadAll(io.LimitReader(rc, 8<<20))
		_ = rc.Close()
		if err != nil {
			continue
		}
		lower := strings.ToLower(f.Name)
		if isPDF(data) || strings.HasSuffix(lower, ".pdf") {
			if len(pack.PDF) == 0 {
				pack.PDF = data
			}
			continue
		}
		if looksLikeZip(data) {
			collectXMLFromZip(data, pack)
			continue
		}
		xml := decodeXML(data)
		if IsReceivableUBL(xml) {
			pack.XMLs = append(pack.XMLs, xml)
		}
	}
}

func looksLikeZip(b []byte) bool {
	return len(b) >= 4 && b[0] == 'P' && b[1] == 'K'
}

func isPDF(b []byte) bool {
	return len(b) > 4 && b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46
}

func decodeXML(b []byte) string {
	if len(b) >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF {
		b = b[3:]
	}
	if !utf8.Valid(b) {
		return string(b) // best effort
	}
	return string(b)
}

func EncodePDFBase64(pdf []byte) string {
	if !isPDF(pdf) {
		return ""
	}
	return base64.StdEncoding.EncodeToString(pdf)
}
