/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * REPLACED: javolution with Jackson XML
 */
package org.mobicents.ussdgateway;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Amit Bhayani
 * @author Matrix Agent
 */
public class AnyExtTest {

    private final XmlMapper xmlMapper = new XmlMapper();

    public AnyExtTest() {
        xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @BeforeClass
    public void setUpClass() throws Exception {
    }

    @AfterClass
    public void tearDownClass() throws Exception {
    }

    @BeforeMethod
    public void setUp() {
    }

    @AfterMethod
    public void tearDown() {
    }

    @Test(groups = { "Sip" })
    public void testXMLSerialize() throws Exception {
        AnyExt anyExt = new AnyExt(MAPMessageType.unstructuredSSRequest_Request);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        xmlMapper.writeValue(baos, anyExt);
        byte[] data = baos.toByteArray();

        System.out.println(new String(data));

        AnyExt copy = xmlMapper.readValue(data, AnyExt.class);

        assertEquals(copy, anyExt);
    }
}
