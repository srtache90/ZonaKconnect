package com.zonak.portal.integration.sap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.stereotype.Component;

@Component
public class SapXmlDocumentParser {
    private final XmlMapper xmlMapper;

    public SapXmlDocumentParser() {
        JacksonXmlModule module = new JacksonXmlModule();
        module.setDefaultUseWrapper(false);

        this.xmlMapper = new XmlMapper(module);
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.xmlMapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
        this.xmlMapper.findAndRegisterModules();
    }

    public SapEnviarDocumento parse(String xml) throws java.io.IOException {
        return xmlMapper.readValue(xml, SapEnviarDocumento.class);
    }
}
