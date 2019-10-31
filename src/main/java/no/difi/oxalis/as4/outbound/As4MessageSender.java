package no.difi.oxalis.as4.outbound;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import no.difi.oxalis.api.outbound.TransmissionRequest;
import no.difi.oxalis.api.outbound.TransmissionResponse;
import no.difi.oxalis.api.settings.Settings;
import no.difi.oxalis.as4.api.MessageIdGenerator;
import no.difi.oxalis.as4.lang.OxalisAs4TransmissionException;
import no.difi.oxalis.as4.util.CompressionUtil;
import no.difi.oxalis.as4.util.Constants;
import no.difi.oxalis.commons.http.HttpConf;
import no.difi.oxalis.commons.security.KeyStoreConf;
import org.apache.cxf.Bus;
import org.apache.cxf.attachment.AttachmentUtil;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.headers.Header;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.jaxws.DispatchImpl;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.policy.PolicyBuilder;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.apache.cxf.ws.policy.WSPolicyFeature;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.neethi.Policy;
import org.apache.wss4j.common.crypto.Merlin;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.Messaging;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPMessage;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Service;
import javax.xml.ws.soap.SOAPBinding;
import java.io.IOException;
import java.security.KeyStore;
import java.util.*;

public class As4MessageSender {

    public static final QName SERVICE_NAME = new QName("oxalis.difi.no/", "outbound-service");
    public static final QName PORT_NAME = new QName("oxalis.difi.no/", "port");

    @Inject
    private MessagingProvider messagingProvider;

    @Inject
    private MessageIdGenerator messageIdGenerator;

    @Inject
    @Named("truststore-ap")
    private KeyStore trustStore;

    @Inject
    private KeyStore keyStore;

    @Inject
    private Settings<KeyStoreConf> settings;

    @Inject
    private CompressionUtil compressionUtil;

    @Inject
    private Settings<HttpConf> httpConfSettings;

    @Inject
    private TransmissionResponseConverter transmissionResponseConverter;

    @Inject
    private Bus bus;

    private Service service;

    public As4MessageSender() {
        service = Service.create(SERVICE_NAME, new LoggingFeature(), new WSPolicyFeature());
        service.addPort(PORT_NAME, SOAPBinding.SOAP12HTTP_BINDING, "BindingProvider.ENDPOINT_ADDRESS_PROPERTY placeholder");
    }

    public TransmissionResponse send(TransmissionRequest request) throws OxalisAs4TransmissionException {

        Dispatch<SOAPMessage> dispatch = createDispatch(request.getEndpoint().getAddress().toString());


        Collection<Attachment> attachments = prepareAttachments(request);
        Messaging messaging = messagingProvider.createMessagingHeader(request, attachments);

        SoapHeader header = null;
        try {
            header = new SoapHeader(
                    Constants.MESSAGING_QNAME,
                    messaging,
                    new JAXBDataBinding(Messaging.class),
                    true);
        }catch (JAXBException e){
            throw new OxalisAs4TransmissionException("Unable to marshal AS4 header", e);
        }


        dispatch.getRequestContext().put(Header.HEADER_LIST, new ArrayList(Arrays.asList(header)));
        dispatch.getRequestContext().put(Message.ATTACHMENTS, attachments);


        configureSecurity(request, dispatch);

        SOAPMessage response = dispatch.invoke(null);


        return transmissionResponseConverter.convert(request, response);

    }

    private void configureSecurity(TransmissionRequest request, Dispatch<SOAPMessage> dispatch) throws OxalisAs4TransmissionException {

        Merlin signatureCrypto = new Merlin();
        signatureCrypto.setCryptoProvider(BouncyCastleProvider.PROVIDER_NAME);
        signatureCrypto.setKeyStore(keyStore);
        signatureCrypto.setTrustStore(trustStore);

        dispatch.getRequestContext().put(SecurityConstants.SIGNATURE_CRYPTO, signatureCrypto);
        dispatch.getRequestContext().put(SecurityConstants.SIGNATURE_PASSWORD, settings.getString(KeyStoreConf.KEY_PASSWORD));
        dispatch.getRequestContext().put(SecurityConstants.SIGNATURE_USERNAME, settings.getString(KeyStoreConf.KEY_ALIAS));

        dispatch.getRequestContext().put(SecurityConstants.ENCRYPT_CERT, request.getEndpoint().getCertificate());


        try {

            PolicyBuilder builder = bus.getExtension(PolicyBuilder.class);
            Policy policy = builder.getPolicy(getClass().getResourceAsStream("/policy.xml"));
            dispatch.getRequestContext().put(PolicyConstants.POLICY_OVERRIDE, policy);
        }catch (IOException | ParserConfigurationException | SAXException e){
            throw new OxalisAs4TransmissionException("Unable to parse WSPolicy \"/policy.xml\"", e);
        }
    }

    public Collection<Attachment> prepareAttachments(TransmissionRequest request) throws OxalisAs4TransmissionException {

        HashMap<String, List<String>> headers = new HashMap<>();
        headers.put("Content-ID", Collections.singletonList(messageIdGenerator.generate()));
        headers.put("CompressionType", Collections.singletonList("application/gzip"));
        headers.put("MimeType", Collections.singletonList("application/xml"));

        try{

            Attachment attachment = AttachmentUtil.createAttachment(compressionUtil.getCompressedStream(request.getPayload()), headers);
            return new ArrayList<>(Arrays.asList(attachment));

        }catch (IOException e){

            throw new OxalisAs4TransmissionException("Unable to compress payload", e);
        }
    }

    private Dispatch<SOAPMessage> createDispatch(String address) {

        Dispatch<SOAPMessage> dispatch = service.createDispatch(PORT_NAME, SOAPMessage.class, Service.Mode.MESSAGE);
        dispatch.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, address);

        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
        httpClientPolicy.setConnectionTimeout(httpConfSettings.getInt(HttpConf.TIMEOUT_CONNECT));
        httpClientPolicy.setReceiveTimeout(httpConfSettings.getInt(HttpConf.TIMEOUT_READ));

        ((HTTPConduit)((DispatchImpl)dispatch).getClient().getConduit()).setClient(httpClientPolicy);

        return dispatch;
    }


}
