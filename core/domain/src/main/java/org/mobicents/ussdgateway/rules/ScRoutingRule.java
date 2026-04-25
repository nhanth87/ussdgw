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
 * REPLACED: javolution.xml with Jackson XML annotations
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.mobicents.ussdgateway.UssdOAMMessages;

/**
 * Acts as Fact for Rules
 *
 * @author amit bhayani
 * @author Matrix Agent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScRoutingRule implements java.io.Serializable {

    @JsonProperty("ruleType")
    private ScRoutingRuleType ruleType = ScRoutingRuleType.SIP;

    @JsonProperty("shortcode")
    private String shortCode;

    @JsonProperty("networkid")
    private int networkId = 0;

    @JsonProperty("ruleurl")
    private String ruleUrl;

    @JsonProperty("sipProxy")
    private String sipProxy;

    @JsonProperty("exactmatch")
    private boolean exactMatch = true;

    public ScRoutingRule() {
    }

    public ScRoutingRule(String ussdString) {
        this.shortCode = ussdString;
    }

    public ScRoutingRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(ScRoutingRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
    }

    public String getRuleUrl() {
        return ruleUrl;
    }

    public void setRuleUrl(String ruleUrl) {
        this.ruleUrl = ruleUrl;
    }

    public String getSipProxy() {
        return sipProxy;
    }

    public void setSipProxy(String sipProxy) {
        this.sipProxy = sipProxy;
    }

    public boolean isExactMatch() {
        return exactMatch;
    }

    public void setExactMatch(boolean exactMatch) {
        this.exactMatch = exactMatch;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        this.show(sb);
        return sb.toString();
    }

    public void show(StringBuffer sb) {
        sb.append(UssdOAMMessages.SHOW_SC).append(this.shortCode).append(UssdOAMMessages.SHOW_NETWORK_ID).append(this.networkId)
                .append(UssdOAMMessages.SHOW_RULE_TYPE)
                .append(this.ruleType).append(UssdOAMMessages.SHOW_URL).append(this.ruleUrl)
                .append(UssdOAMMessages.SHOW_SIP_PROXY).append(this.sipProxy).append(UssdOAMMessages.SHOW_EXACT_MATCH)
                .append(this.isExactMatch());

        sb.append(UssdOAMMessages.NEW_LINE);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + networkId;
        result = prime * result
                + ((shortCode == null) ? 0 : shortCode.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ScRoutingRule other = (ScRoutingRule) obj;
        if (networkId != other.networkId)
            return false;
        if (shortCode == null) {
            if (other.shortCode != null)
                return false;
        } else if (!shortCode.equals(other.shortCode))
            return false;
        return true;
    }
}
