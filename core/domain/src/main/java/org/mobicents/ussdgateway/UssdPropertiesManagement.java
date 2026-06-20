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
 * REPLACED: javolution with StringBuilder, HashMap and Jackson XML
 * 100% API Compatible with original
 */
package org.mobicents.ussdgateway;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import org.apache.log4j.Logger;

/**
 * @author amit bhayani
 * @author Matrix Agent
 */
public class UssdPropertiesManagement implements UssdPropertiesManagementMBean {

    private static final Logger logger = Logger.getLogger(UssdPropertiesManagement.class);

    protected static final String NO_ROUTING_RULE_CONFIGURED_ERROR_MESSAGE = "noroutingruleconfigerrmssg";
    protected static final String SERVER_OVERLOADED_MESSAGE = "serveroverloadedmsg";
    protected static final String SERVER_ERROR_MESSAGE = "servererrmssg";
    protected static final String DIALOG_TIMEOUT_ERROR_MESSAGE = "dialogtimeouterrmssg";

    protected static final String DIALOG_TIMEOUT = "dialogtimeout";

    private static final String USSD_GT_LIST = "ussdgtlist";
    protected static final String USSD_GT = "ussdgt";
    protected static final String USSD_SSN = "ussdssn";
    protected static final String HLR_SSN = "hlrssn";
    protected static final String MSC_SSN = "mscssn";
    protected static final String MAX_MAP_VERSION = "maxmapv";
    protected static final String HR_HLR_GT = "hrhlrgt";
    protected static final String CDR_LOGGING_TO = "cdrloggingto";
    protected static final String MAX_ACTIVITY_COUNT = "maxactivitycount";
    protected static final String CDR_SEPARATOR = "cdrSeparator";

    // Virtual Session Bridge configuration
    protected static final String SESSION_BRIDGE_ENABLED = "sessionbridgeenabled";
    protected static final String ASYNC_GATE_TIMEOUT_MS = "asyncgatetimeoutms";
    protected static final String ASYNC_WAIT_USER_MESSAGE = "asyncwaitusermessage";
    protected static final String ASYNC_HARD_FAIL_MESSAGE = "asynchardfailmessage";
    protected static final String BRIDGE_STATE_TTL_SEC = "bridgestatettlsec";
    protected static final String PUSH_RETRY_DELAYS_MS = "pushretrydelaysms";

    private static final String PERSIST_FILE_NAME = "ussdproperties.xml";

    private static UssdPropertiesManagement instance;

    private final String name;

    private String persistDir = null;

    private StringBuilder persistFile = new StringBuilder();

    // Jackson XmlMapper for persistence
    private static final XmlMapper xmlMapper;
    static {
        xmlMapper = new XmlMapper();
        // INDENT_OUTPUT disabled to avoid Stax2WriterAdapter.writeRaw() UnsupportedOperationException
        // with Jackson-dataformat-xml 2.15.2 + StAX on WildFly 10
        // xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private String noRoutingRuleConfiguredMessage = "Not valid short code. Please dial valid short code.";
    private String serverOverloadedMessage = "Server is overloaded. Please try later";
    private String serverErrorMessage = "Server error, please try again after sometime";
    private String dialogTimeoutErrorMessage = "Request timeout please try again after sometime.";

    private String ussdGwGt = "00000000";
    private HashMap<Integer, String> networkIdVsUssdGwGt = new HashMap<>();
    private int ussdGwSsn = 8;
    private int hlrSsn = 6;
    private int mscSsn = 8;
    private int maxMapVersion = 3;
    /**
     * Dialog time out in milliseconds. Once HTTP request is sent, it expects
     * back response in dialogTimeout milli seconds.
     */
    private long dialogTimeout = 25000;

    private String hrHlrGt = null;

    private CdrLoggedType cdrLoggingTo = CdrLoggedType.LocalRa;
    private String cdrSeparator = ":";

    private int maxActivityCount = 5000;

    // ----- Virtual Session Bridge -----
    /** Master feature flag. When false the gateway behaves exactly as before (no bridge). */
    private boolean sessionBridgeEnabled = false;
    /** Release the MO dialogue before the network timeout. Must be &lt; dialogTimeout. */
    private long asyncGateTimeoutMs = 7000;
    private String asyncWaitUserMessage = "Hệ thống đang bận, sẽ update lại cho bạn ngay";
    private String asyncHardFailMessage = "Hệ thống đang có nhiều người sử dụng, vui lòng dùng lại sau";
    /** Infinispan TTL for bridged session state, in seconds. */
    private int bridgeStateTtlSec = 180;
    /** Comma-separated push retry back-off in milliseconds. */
    private String pushRetryDelaysMs = "3000,8000,15000";

    private UssdPropertiesManagement(String name) {
        this.name = name;
    }

    protected static UssdPropertiesManagement getInstance(String name) {
        if (instance == null) {
            instance = new UssdPropertiesManagement(name);
        }
        return instance;
    }

    public static UssdPropertiesManagement getInstance() {
        return instance;
    }

    public String getName() {
        return name;
    }

    @Override
    public String getNoRoutingRuleConfiguredMessage() {
        return this.noRoutingRuleConfiguredMessage;
    }

    @Override
    public void setNoRoutingRuleConfiguredMessage(String noRoutingRuleConfiguredMessage) {
        this.noRoutingRuleConfiguredMessage = noRoutingRuleConfiguredMessage;
        this.store();
    }

    @Override
    public String getServerOverloadedMessage() {
        return this.serverOverloadedMessage;
    }

    @Override
    public void setServerOverloadedMessage(String serverOverloadedMessage) {
        this.serverOverloadedMessage = serverOverloadedMessage;
        this.store();
    }

    @Override
    public String getServerErrorMessage() {
        return this.serverErrorMessage;
    }

    @Override
    public void setServerErrorMessage(String serverErrorMessage) {
        this.serverErrorMessage = serverErrorMessage;
        this.store();
    }

    @Override
    public String getDialogTimeoutErrorMessage() {
        return this.dialogTimeoutErrorMessage;
    }

    @Override
    public void setDialogTimeoutErrorMessage(String dialogTimeoutErrorMessage) {
        this.dialogTimeoutErrorMessage = dialogTimeoutErrorMessage;
        this.store();
    }

    @Override
    public long getDialogTimeout() {
        return dialogTimeout;
    }

    @Override
    public void setDialogTimeout(long dialogTimeout) {
        this.dialogTimeout = dialogTimeout;
        this.store();
    }

    public String getPersistDir() {
        return persistDir;
    }

    public void setPersistDir(String persistDir) {
        this.persistDir = persistDir;
    }

    @Override
    public String getUssdGt() {
        return this.ussdGwGt;
    }

    @Override
    public void setUssdGt(String serviceCenterGt) {
        this.setUssdGt(0, serviceCenterGt);
    }

    @Override
    public String getUssdGt(int networkId) {
        String res = this.networkIdVsUssdGwGt.get(networkId);
        if (res != null)
            return res;
        else
            return this.ussdGwGt;
    }

    @Override
    public Map<Integer, String> getNetworkIdVsUssdGwGt() {
        return this.networkIdVsUssdGwGt;
    }

    @Override
    public void setUssdGt(int networkId, String serviceCenterGt) {
        if (networkId == 0) {
            this.ussdGwGt = serviceCenterGt;
        } else {
            if (serviceCenterGt == null || serviceCenterGt.equals("") || serviceCenterGt.equals("0")) {
                this.networkIdVsUssdGwGt.remove(networkId);
            } else {
                this.networkIdVsUssdGwGt.put(networkId, serviceCenterGt);
            }
        }

        this.store();
    }

    public int getUssdSsn() {
        return ussdGwSsn;
    }

    public void setUssdSsn(int serviceCenterSsn) {
        this.ussdGwSsn = serviceCenterSsn;
        this.store();
    }

    public int getHlrSsn() {
        return hlrSsn;
    }

    public void setHlrSsn(int hlrSsn) {
        this.hlrSsn = hlrSsn;
        this.store();
    }

    public int getMaxMapVersion() {
        return maxMapVersion;
    }

    public void setMaxMapVersion(int maxMapVersion) {
        this.maxMapVersion = maxMapVersion;
        this.store();
    }

    public String getHrHlrGt() {
        return hrHlrGt;
    }

    public void setHrHlrGt(String hrHlrNumber) {
        this.hrHlrGt = hrHlrNumber;
        this.store();
    }

    public int getMscSsn() {
        return mscSsn;
    }

    public void setMscSsn(int mscSsn) {
        this.mscSsn = mscSsn;
        this.store();
    }

    public CdrLoggedType getCdrLoggingTo() {
        return normalizeCdrLoggingTo(cdrLoggingTo);
    }

    private static CdrLoggedType normalizeCdrLoggingTo(CdrLoggedType type) {
        if (type == null) {
            return CdrLoggedType.LocalRa;
        }
        if (type == CdrLoggedType.Textfile || type == CdrLoggedType.Database) {
            return CdrLoggedType.LocalRa;
        }
        return type;
    }

    public String getCdrLoggingToStr() {
        if (cdrLoggingTo != null)
            return cdrLoggingTo.toString();
        else
            return null;
    }

    public void setCdrLoggingTo(CdrLoggedType cdrLoggingTo) {
        this.cdrLoggingTo = cdrLoggingTo;
        this.store();
    }

    public void setCdrLoggingToStr(String cdrLoggingTo) {
        this.cdrLoggingTo = CdrLoggedType.valueOf(cdrLoggingTo);
        this.store();
    }

    @Override
    public String getCdrSeparator() {
        return cdrSeparator;
    }

    @Override
    public void setCdrSeparator(String cdrSeparator) {
        if (cdrSeparator != null && cdrSeparator.length() > 0) {
            this.cdrSeparator = cdrSeparator;
            this.store();
        }
    }

    @Override
    public int getMaxActivityCount() {
        return maxActivityCount;
    }

    @Override
    public void setMaxActivityCount(int maxActivityCount) {
        this.maxActivityCount = maxActivityCount;
        this.store();
    }

    // ----- Virtual Session Bridge accessors -----

    @Override
    public boolean isSessionBridgeEnabled() {
        return sessionBridgeEnabled;
    }

    @Override
    public void setSessionBridgeEnabled(boolean sessionBridgeEnabled) {
        this.sessionBridgeEnabled = sessionBridgeEnabled;
        this.store();
    }

    @Override
    public long getAsyncGateTimeoutMs() {
        return asyncGateTimeoutMs;
    }

    @Override
    public void setAsyncGateTimeoutMs(long asyncGateTimeoutMs) {
        this.asyncGateTimeoutMs = asyncGateTimeoutMs;
        this.store();
    }

    @Override
    public String getAsyncWaitUserMessage() {
        return asyncWaitUserMessage;
    }

    @Override
    public void setAsyncWaitUserMessage(String asyncWaitUserMessage) {
        this.asyncWaitUserMessage = asyncWaitUserMessage;
        this.store();
    }

    @Override
    public String getAsyncHardFailMessage() {
        return asyncHardFailMessage;
    }

    @Override
    public void setAsyncHardFailMessage(String asyncHardFailMessage) {
        this.asyncHardFailMessage = asyncHardFailMessage;
        this.store();
    }

    @Override
    public int getBridgeStateTtlSec() {
        return bridgeStateTtlSec;
    }

    @Override
    public void setBridgeStateTtlSec(int bridgeStateTtlSec) {
        this.bridgeStateTtlSec = bridgeStateTtlSec;
        this.store();
    }

    @Override
    public String getPushRetryDelaysMs() {
        return pushRetryDelaysMs;
    }

    @Override
    public void setPushRetryDelaysMs(String pushRetryDelaysMs) {
        this.pushRetryDelaysMs = pushRetryDelaysMs;
        this.store();
    }

    /**
     * Parses {@link #pushRetryDelaysMs} into a {@code long[]} of back-off delays. Falls back to
     * {@code 3000,8000,15000} if the configured value is missing or malformed.
     */
    public long[] getPushRetryDelaysMsArray() {
        long[] fallback = new long[] { 3000L, 8000L, 15000L };
        if (pushRetryDelaysMs == null || pushRetryDelaysMs.trim().isEmpty()) {
            return fallback;
        }
        String[] parts = pushRetryDelaysMs.split(",");
        java.util.List<Long> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                logger.warn("Ignoring invalid pushRetryDelaysMs entry: " + trimmed);
            }
        }
        if (values.isEmpty()) {
            return fallback;
        }
        long[] result = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    public void start() throws Exception {

        this.persistFile.setLength(0);

        if (persistDir != null) {
            this.persistFile.append(persistDir).append(File.separator).append(this.name).append("_")
                    .append(PERSIST_FILE_NAME);
        } else {
            persistFile
                    .append(System.getProperty(UssdManagement.USSD_PERSIST_DIR_KEY,
                            System.getProperty(UssdManagement.USER_DIR_KEY))).append(File.separator).append(this.name)
                    .append("_").append(PERSIST_FILE_NAME);
        }

        logger.info(String.format("Loading USSD Properties from %s", persistFile.toString()));

        try {
            this.load();
        } catch (FileNotFoundException e) {
            logger.warn(String.format("Failed to load the USSD configuration file. \n%s", e.getMessage()));
        }
    }

    public void stop() throws Exception {
        this.store();
    }

    /**
     * Persist using Jackson XML
     */
    public void store() {
        try {
            // Build properties as a map
            HashMap<String, Object> properties = new HashMap<>();
            properties.put(NO_ROUTING_RULE_CONFIGURED_ERROR_MESSAGE, this.noRoutingRuleConfiguredMessage);
            properties.put(SERVER_OVERLOADED_MESSAGE, this.serverOverloadedMessage);
            properties.put(SERVER_ERROR_MESSAGE, this.serverErrorMessage);
            properties.put(DIALOG_TIMEOUT_ERROR_MESSAGE, this.dialogTimeoutErrorMessage);
            properties.put(DIALOG_TIMEOUT, this.dialogTimeout);
            properties.put(USSD_GT, this.ussdGwGt);
            properties.put(USSD_SSN, this.ussdGwSsn);
            properties.put(HLR_SSN, this.hlrSsn);
            properties.put(MSC_SSN, this.mscSsn);
            properties.put(MAX_MAP_VERSION, this.maxMapVersion);
            properties.put(HR_HLR_GT, this.hrHlrGt);
            properties.put(CDR_LOGGING_TO, this.cdrLoggingTo.toString());
            properties.put(CDR_SEPARATOR, this.cdrSeparator);
            properties.put(MAX_ACTIVITY_COUNT, this.maxActivityCount);
            properties.put(SESSION_BRIDGE_ENABLED, this.sessionBridgeEnabled);
            properties.put(ASYNC_GATE_TIMEOUT_MS, this.asyncGateTimeoutMs);
            properties.put(ASYNC_WAIT_USER_MESSAGE, this.asyncWaitUserMessage);
            properties.put(ASYNC_HARD_FAIL_MESSAGE, this.asyncHardFailMessage);
            properties.put(BRIDGE_STATE_TTL_SEC, this.bridgeStateTtlSec);
            properties.put(PUSH_RETRY_DELAYS_MS, this.pushRetryDelaysMs);

            if (networkIdVsUssdGwGt.size() > 0) {
                ArrayList<UssdGwGtNetworkIdElement> al = new ArrayList<>();
                for (Map.Entry<Integer, String> val : networkIdVsUssdGwGt.entrySet()) {
                    UssdGwGtNetworkIdElement el = new UssdGwGtNetworkIdElement();
                    el.networkId = val.getKey();
                    el.ussdGwGt = val.getValue();
                    al.add(el);
                }
                properties.put(USSD_GT_LIST, al);
            }

            StringWriter writer = new StringWriter();
            xmlMapper.writeValue(writer, properties);
            String xmlContent = writer.toString();

            FileOutputStream fos = new FileOutputStream(persistFile.toString());
            fos.write(xmlContent.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            logger.error("Error while persisting the properties state in file", e);
        }
    }

    /**
     * Load properties from persisted file
     */
    @SuppressWarnings("unchecked")
    public void load() throws FileNotFoundException {
        FileInputStream fis = null;
        try {
            File file = new File(persistFile.toString());
            fis = new FileInputStream(file);

            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            String xml = new String(data, "UTF-8");
            xml = LegacyXmlConfigAdapter.normalizeUssdPropertiesXml(xml);

            HashMap<String, Object> properties = xmlMapper.readValue(xml, HashMap.class);

            if (properties != null) {
                if (properties.containsKey(USSD_GT_LIST)) {
                    java.util.List<UssdGwGtNetworkIdElement> al =
                            LegacyXmlConfigAdapter.parseUssdGtList(properties.get(USSD_GT_LIST));
                    networkIdVsUssdGwGt.clear();
                    for (UssdGwGtNetworkIdElement elem : al) {
                        networkIdVsUssdGwGt.put(elem.networkId, elem.ussdGwGt);
                    }
                }

                String s1 = (String) properties.get(NO_ROUTING_RULE_CONFIGURED_ERROR_MESSAGE);
                if (s1 != null)
                    this.noRoutingRuleConfiguredMessage = s1;
                s1 = (String) properties.get(SERVER_OVERLOADED_MESSAGE);
                if (s1 != null)
                    this.serverOverloadedMessage = s1;
                s1 = (String) properties.get(SERVER_ERROR_MESSAGE);
                if (s1 != null)
                    this.serverErrorMessage = s1;
                s1 = (String) properties.get(DIALOG_TIMEOUT_ERROR_MESSAGE);
                if (s1 != null)
                    this.dialogTimeoutErrorMessage = s1;

                Object o = properties.get(DIALOG_TIMEOUT);
                if (o != null)
                    this.dialogTimeout = (o instanceof Number) ? ((Number) o).longValue() : Long.parseLong(o.toString());

                o = properties.get(HR_HLR_GT);
                if (o != null)
                    this.hrHlrGt = (String) o;

                o = properties.get("hrhlrnumber");
                if (o != null)
                    this.hrHlrGt = (String) o;

                o = properties.get(CDR_LOGGING_TO);
                if (o != null)
                    this.cdrLoggingTo = CdrLoggedType.valueOf((String) o);

                o = properties.get(CDR_SEPARATOR);
                if (o != null && ((String) o).length() > 0)
                    this.cdrSeparator = (String) o;

                o = properties.get(MAX_ACTIVITY_COUNT);
                if (o != null)
                    this.maxActivityCount = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(USSD_GT);
                if (o != null)
                    this.ussdGwGt = (String) o;

                o = properties.get(USSD_SSN);
                if (o != null)
                    this.ussdGwSsn = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(HLR_SSN);
                if (o != null)
                    this.hlrSsn = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(MSC_SSN);
                if (o != null)
                    this.mscSsn = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(MAX_MAP_VERSION);
                if (o != null)
                    this.maxMapVersion = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(SESSION_BRIDGE_ENABLED);
                if (o != null)
                    this.sessionBridgeEnabled = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

                o = properties.get(ASYNC_GATE_TIMEOUT_MS);
                if (o != null)
                    this.asyncGateTimeoutMs = (o instanceof Number) ? ((Number) o).longValue() : Long.parseLong(o.toString());

                s1 = (String) properties.get(ASYNC_WAIT_USER_MESSAGE);
                if (s1 != null)
                    this.asyncWaitUserMessage = s1;

                s1 = (String) properties.get(ASYNC_HARD_FAIL_MESSAGE);
                if (s1 != null)
                    this.asyncHardFailMessage = s1;

                o = properties.get(BRIDGE_STATE_TTL_SEC);
                if (o != null)
                    this.bridgeStateTtlSec = (o instanceof Number) ? ((Number) o).intValue() : Integer.parseInt(o.toString());

                o = properties.get(PUSH_RETRY_DELAYS_MS);
                if (o != null)
                    this.pushRetryDelaysMs = o.toString();
            }
        } catch (FileNotFoundException e) {
            logger.info("No persisted properties file found at " + persistFile + ". Using defaults.");
        } catch (Exception e) {
            logger.error("Error while loading properties from persisted file", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public enum CdrLoggedType {
        /** @deprecated Legacy config value; treated as {@link #LocalRa}. */
        Database,
        /** @deprecated Legacy config value; treated as {@link #LocalRa}. */
        Textfile,
        LocalRa,
    }
}
