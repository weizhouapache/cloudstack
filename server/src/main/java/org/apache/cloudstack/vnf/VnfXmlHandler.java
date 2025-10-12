// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.vnf;

import com.cloud.utils.exception.CloudRuntimeException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.zip.DataFormatException;


public class VnfXmlHandler implements VnfDataFormatHandler {
    private final XmlMapper xmlMapper;
    private final boolean prettyPrint;

    @Override
    public VnfService.DataFormat getDataFormat() {
        return VnfService.DataFormat.XML;
    }

    public VnfXmlHandler() {
        this(true); // Default to pretty print
    }

    public VnfXmlHandler(boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
        this.xmlMapper = createXmlMapper();
    }

    private XmlMapper createXmlMapper() {
        XmlMapper mapper = new XmlMapper();

        if (prettyPrint) {
            // Configure for pretty printing
            mapper.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
        }

        return mapper;
    }

    @Override
    public String format(Object data) {
        try {
            if (data instanceof String) {
                // If it's already a string, assume it's XML
                String xmlString = (String) data;
                if (prettyPrint) {
                    return prettyFormatXml(xmlString);
                }
                return xmlString;
            } else {
                // Convert object to XML using Jackson
                if (prettyPrint) {
                    return xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
                } else {
                    return xmlMapper.writeValueAsString(data);
                }
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("XML formatting failed", e);
        }
    }

    @Override
    public Object parse(String data) {
        try {
            // Try to parse as generic Object first
            return xmlMapper.readValue(data, Object.class);
        } catch (Exception e) {
            throw new CloudRuntimeException("XML parsing failed", e);
        }
    }

    /**
     * Pretty format XML string with proper indentation
     */
    private String prettyFormatXml(String xmlString) throws DataFormatException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Security: disable external entity processing to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new java.io.ByteArrayInputStream(xmlString.getBytes()));

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            // Security: disable external DTDs and stylesheets
            transformerFactory.setAttribute("http://javax.xml.XMLConstants/feature/secure-processing", true);

            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();

        } catch (Exception e) {
            // If pretty formatting fails, return original string
            return xmlString;
        }
    }
}

