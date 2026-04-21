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
package org.mobicents.ussdgateway.rules;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author Amit Bhayani
 * @author Matrix Agent
 */
public class ScRoutingRuleTest {

    private final XmlMapper xmlMapper = new XmlMapper();

    public ScRoutingRuleTest() {
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

    @Test
    public void testSerialization() throws Exception {
        ScRoutingRule scRule = new ScRoutingRule();
        scRule.setRuleType(ScRoutingRuleType.SIP);
        scRule.setShortCode("*123#");
        scRule.setSipProxy("127.0.0.1:5060");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        xmlMapper.writeValue(baos, scRule);
        byte[] data = baos.toByteArray();

        System.out.println(new String(data));

        ScRoutingRule copy = xmlMapper.readValue(data, ScRoutingRule.class);
        assertEquals(copy, scRule);

        scRule = new ScRoutingRule();
        scRule.setRuleType(ScRoutingRuleType.HTTP);
        scRule.setShortCode("*123#");
        scRule.setRuleUrl("http://localhost:8080/ussddemo/test");

        baos = new ByteArrayOutputStream();
        xmlMapper.writeValue(baos, scRule);
        data = baos.toByteArray();

        System.out.println(new String(data));

        copy = xmlMapper.readValue(data, ScRoutingRule.class);
        assertEquals(copy, scRule);
    }
}
