package org.mifos.connector.ams.paygops.camel.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.json.JSONObject;
import org.mifos.connector.ams.paygops.paygopsDTO.PaygopsRequestDTO;
import org.mifos.connector.ams.paygops.paygopsDTO.PaygopsResponseDto;
import org.mifos.connector.ams.paygops.utils.ConnectionUtils;
import org.mifos.connector.ams.paygops.utils.ErrorCodeEnum;
import org.mifos.connector.ams.paygops.utils.PayloadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.mifos.connector.ams.paygops.camel.config.CamelProperties.*;
import static org.mifos.connector.ams.paygops.camel.config.CamelProperties.AMS_REQUEST;
import static org.mifos.connector.ams.paygops.zeebe.ZeebeVariables.*;

@Component
public class PaygopsRouteBuilder extends RouteBuilder {

    Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${paygops.base-url}")
    private String paygopsBaseUrl;

    @Value("${paygops.endpoint.verification}")
    private String verificationEndpoint;

    @Value("${paygops.endpoint.confirmation}")
    private String confirmationEndpoint;

    @Value("${paygops.auth-header}")
    private String accessToken;

    @Value("${ams.timeout}")
    private Integer amsTimeout;

    @Value("${paygops.operator}")
    private String operatorName;

    enum accountStatus{
        ACTIVE,
        REJECTED
    }


    public PaygopsRouteBuilder() {

    }


    @Override
    public void configure() {

        from("rest:POST:/api/v1/payments/validate")
                .process(exchange -> {
                    JSONObject channelRequest = new JSONObject(exchange.getIn().getBody(String.class));
                    String transactionId = "123";
                    log.info(channelRequest.toString());
                    exchange.setProperty(CHANNEL_REQUEST, channelRequest);
                    exchange.setProperty(TRANSACTION_ID, transactionId);
                })
                .to("direct:transfer-validation-base");

        from("rest:POST:/api/paymentHub/Confirmation")
                .process(exchange -> {
                    JSONObject channelRequest = new JSONObject(exchange.getIn().getBody(String.class));
                    String transactionId = "123";
                    exchange.setProperty(CHANNEL_REQUEST, channelRequest);
                    exchange.setProperty(TRANSACTION_ID, transactionId);
                })
                .to("direct:transfer-settlement-base");

        from("direct:transfer-validation-base")
                .id("transfer-validation-base")
                .log(LoggingLevel.INFO, "## Starting Paygops Validation base route")
                .to("direct:transfer-validation")
                .choice()
                .when(header("CamelHttpResponseCode").isEqualTo("200"))
                .log(LoggingLevel.INFO, "Paygops Validation Response Received")
                .unmarshal().json(JsonLibrary.Jackson, PaygopsResponseDto.class)
                .process(exchange -> {
                    // processing success case
                    String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
                    try {
                        PaygopsResponseDto result = exchange.getIn().getBody(PaygopsResponseDto.class);
                        if (result.getReconciled()) {
                            logger.info("Paygops validation successful for transaction {}", transactionId);
                            exchange.setProperty(PARTY_LOOKUP_FAILED, false);
                            exchange.setProperty("accountStatus",accountStatus.ACTIVE.toString());
                            exchange.setProperty("subStatus", "");
                            exchange.setProperty("accountHoldingInstitutionId", exchange.getProperty("accountHoldingInstitutionId"));
                            exchange.setProperty(TRANSACTION_ID, exchange.getProperty(TRANSACTION_ID));
                            exchange.setProperty("amount", result.getAmount());
                            exchange.setProperty("currency", result.getCurrency());
                            String msisdn = result.getSender_phone_number();
                            if (msisdn != null && msisdn.trim().length() > 0) {
                                msisdn = msisdn.substring(1);
                            }
                            exchange.setProperty("msisdn", msisdn);
                        } else {
                            logger.info("Reconciled field returned false. Paygops validation failed for transaction {}", transactionId);
                            setErrorCamelInfo(exchange,"Validation Unsuccessful: Reconciled field returned false",
                                    ErrorCodeEnum.RECONCILIATION.getCode(), result.toString());

                            exchange.setProperty(PARTY_LOOKUP_FAILED, true);
                            exchange.setProperty("accountStatus",accountStatus.REJECTED.toString());
                            exchange.setProperty("subStatus", "");
                        }
                    } catch (Exception e) {
                        logger.error("Paygops validation failed for transaction {}. Body could not be parsed due to : {} ",
                            transactionId, String.valueOf(e));
                        setErrorCamelInfo(exchange,"Body data could not be parsed,setting validation as failed",
                                ErrorCodeEnum.DEFAULT.getCode(),exchange.getIn().getBody(String.class));
                        exchange.setProperty(PARTY_LOOKUP_FAILED, true);
                        exchange.setProperty("accountStatus",accountStatus.REJECTED.toString());
                        exchange.setProperty("subStatus", "");
                    }
                })
                .otherwise()
                .log(LoggingLevel.ERROR, "Paygops Validation unsuccessful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange -> {
                    // processing unsuccessful case
                    String body = exchange.getIn().getBody(String.class);
                    JSONObject jsonObject = new JSONObject(body);
                    Integer errorCode = jsonObject.getInt("error");
                    String errorDescription   = jsonObject.getString("error_message");
                    String errorInfo = jsonObject.toString(1);
                    setErrorCamelInfo(exchange,errorDescription,errorCode,errorInfo);
                    exchange.setProperty(PARTY_LOOKUP_FAILED, true);
                });

        from("direct:transfer-validation")
                .id("transfer-validation")
                .log(LoggingLevel.INFO, "## Starting Paygops Validation route")
                .log(LoggingLevel.INFO, "Bearer token is - " + accessToken)
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Authorization", simple("Bearer "+ accessToken))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Accept-Encoding", constant("gzip;q=1.0, identity; q=0.5"))
                .setBody(exchange -> {
                    if (exchange.getProperty(CHANNEL_REQUEST) != null)
                    {
                        JSONObject channelRequest = (JSONObject) exchange.getProperty(CHANNEL_REQUEST);
                        String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
                        PaygopsRequestDTO verificationRequestDTO = getPaygopsDtoFromChannelRequest(channelRequest,
                                transactionId);
                        logger.info("Paygops Validation request DTO for transaction {} sent on {}: \n{}",
                            transactionId, Instant.now(), verificationRequestDTO);
                        return verificationRequestDTO;
                    }
                    else {
                        JSONObject paybillRequest = new JSONObject(exchange.getIn().getBody(String.class));
                        PaygopsRequestDTO paygopsRequestDTO = PayloadUtils.convertPaybillPayloadToAmsPaygopsPayload(paybillRequest);
                        log.debug(paygopsRequestDTO.toString());
                        exchange.setProperty(TRANSACTION_ID, paygopsRequestDTO.getTransactionId());
                        exchange.setProperty("accountHoldingInstitutionId", exchange.getProperty("accountHoldingInstitutionId"));
                        logger.info("Paygops Validation request DTO for transaction {} sent on {}: \n{}",
                            paygopsRequestDTO.getTransactionId(), Instant.now(), paygopsRequestDTO);
                        return paygopsRequestDTO;
                    }
                })
                .marshal().json(JsonLibrary.Jackson)
                .toD(getVerificationEndpoint() + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"+
                        ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                .log(LoggingLevel.INFO, "Received Paygops validation response for "
                    + "transaction ${exchangeProperty." + TRANSACTION_ID + "} on ${header.Date} with "
                    + "status: ${header.CamelHttpResponseCode}. Body: \n ${body}");

        from("direct:transfer-settlement-base")
                .id("transfer-settlement-base")
                .log(LoggingLevel.INFO, "## Transfer Settlement route")
                .to("direct:transfer-settlement")
                .choice()
                .when(header("CamelHttpResponseCode").startsWith("2"))
                .log(LoggingLevel.INFO, "Settlement Response Received")
                .process(exchange -> {
                    // processing success case
                    String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
                    try {
                        String body = exchange.getIn().getBody(String.class);
                        ObjectMapper mapper = new ObjectMapper();
                        PaygopsResponseDto result = mapper.readValue(body, PaygopsResponseDto.class);
                        if (result.getReception_datetime()!=null) {
                            logger.info("Paygops Settlement Successful for transaction {}", transactionId);
                            exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, false);
                        } else {
                            logger.info("No reception date in response. Paygops Settlement failed for transaction {}", transactionId);
                            setErrorCamelInfo(exchange,"Settlement Unsuccessful: Response did not contain reception date",
                                    ErrorCodeEnum.RECONCILIATION.getCode(), result.toString());

                            exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, true);
                        }
                    } catch (Exception e) {
                        logger.error("Paygops Settlement failed for transaction {}. Body could not be parsed due to : {} ",
                            transactionId, String.valueOf(e));
                        setErrorCamelInfo(exchange,"Body data could not be parsed,setting confirmation as failed",
                                ErrorCodeEnum.DEFAULT.getCode(), exchange.getIn().getBody(String.class));
                        exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, true);
                        exchange.setProperty("accountStatus",accountStatus.REJECTED.toString());
                        exchange.setProperty("subStatus", "");
                    }


                })
                .otherwise()
                .log(LoggingLevel.ERROR, "Paygops Settlement unsuccessful for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .process(exchange -> {
                    // processing unsuccessful case
                    String body = exchange.getIn().getBody(String.class);
                    JSONObject jsonObject = new JSONObject(body);
                    Integer errorCode = jsonObject.getInt("error");
                    String errorDescription = jsonObject.getString("error_message");
                    String errorInfo = jsonObject.toString(1);
                    setErrorCamelInfo(exchange,errorDescription,errorCode,errorInfo);
                    exchange.setProperty(TRANSFER_SETTLEMENT_FAILED, true);
                });

        from("rest:POST:/api/v1/paybill/validate/paygops")
                .id("validate-user")
                .log(LoggingLevel.INFO, "## Paygops user validation")
                .setBody(e -> {
                    String body=e.getIn().getBody(String.class);
                    String accountHoldingInstitutionId= String.valueOf(e.getIn().getHeader("accountHoldingInstitutionId"));
                    e.setProperty("accountHoldingInstitutionId",accountHoldingInstitutionId);
                    logger.debug("Body : {}",body);
                    logger.debug("accountHoldingInstitutionId : {}",accountHoldingInstitutionId);
                    return body;
                })
                .to("direct:transfer-validation-base")
                .process(e->{
                    logger.debug("Response received from validation base : {}",e.getIn().getBody());
                    // Building the response
                    JSONObject responseObject=new JSONObject();
                    responseObject.put("reconciled", e.getProperty(PARTY_LOOKUP_FAILED).equals(false));
                    responseObject.put("amsName", "paygops");
                    responseObject.put("accountHoldingInstitutionId", e.getProperty("accountHoldingInstitutionId"));
                    responseObject.put(TRANSACTION_ID, e.getProperty(TRANSACTION_ID));
                    responseObject.put("amount", e.getProperty("amount"));
                    responseObject.put("currency", e.getProperty("currency"));
                    responseObject.put("msisdn", e.getProperty("msisdn"));
                    logger.debug("response object :{}",responseObject);
                    e.getIn().setBody(responseObject.toString());
                });

        from("direct:transfer-settlement")
                .id("transfer-settlement")
                .log(LoggingLevel.INFO, "## Starting transfer settlement route for "
                    + "transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Authorization", simple("Bearer "+ accessToken))
                .setHeader("Content-Type", constant("application/json"))
                .setBody(exchange -> {
                    JSONObject channelRequest = (JSONObject) exchange.getProperty(CHANNEL_REQUEST);
                    String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
                    PaygopsRequestDTO confirmationRequestDTO = getPaygopsDtoFromChannelRequest(channelRequest,
                            transactionId);
                    logger.info("Paygops Confirmation request DTO for transaction {} sent on {}: \n{}",
                        transactionId, Instant.now(), confirmationRequestDTO);
                    exchange.setProperty(AMS_REQUEST,confirmationRequestDTO.toString());
                    return confirmationRequestDTO;
                })
                .marshal().json(JsonLibrary.Jackson)
                .toD(getConfirmationEndpoint() + "?bridgeEndpoint=true&throwExceptionOnFailure=false&" +
                        ConnectionUtils.getConnectionTimeoutDsl(amsTimeout))
                .log(LoggingLevel.INFO, "Received Paygops confirmation response for "
                    + "transaction ${exchangeProperty." + TRANSACTION_ID + "} on ${header.Date} with "
                    + "status: ${header.CamelHttpResponseCode}. Body: \n ${body}");

    }


    // returns the complete URL for verification request
    private String getVerificationEndpoint() {
        return paygopsBaseUrl + verificationEndpoint;
    }

    // returns the complete URL for confirmation request
    private String getConfirmationEndpoint() {
        return paygopsBaseUrl + confirmationEndpoint;
    }

    private PaygopsRequestDTO getPaygopsDtoFromChannelRequest(JSONObject channelRequest, String transactionId) {
        PaygopsRequestDTO verificationRequestDTO = new PaygopsRequestDTO();

        String phoneNumber = channelRequest.getJSONObject("payer")
                .getJSONObject("partyIdInfo").getString("partyIdentifier");
        String memoId = channelRequest.getJSONObject("payee")
                .getJSONObject("partyIdInfo").getString("partyIdentifier"); // instead of account id this value corresponds to national id
        JSONObject amountJson = channelRequest.getJSONObject("amount");


        Long amount = amountJson.getLong("amount");
        String currency = amountJson.getString("currency");
        String country = "KE";

        verificationRequestDTO.setTransactionId(transactionId);
        verificationRequestDTO.setAmount(amount);
        verificationRequestDTO.setPhoneNumber(phoneNumber);
        verificationRequestDTO.setCurrency(currency);
        verificationRequestDTO.setOperator(operatorName);
        verificationRequestDTO.setMemo(memoId);
        verificationRequestDTO.setCountry(country);
        verificationRequestDTO.setWalletName(phoneNumber);

        return verificationRequestDTO;
    }

    private void setErrorCamelInfo(Exchange exchange, String errorDesc, Integer errorCode, String errorInfo) {
        logger.info(errorInfo);
        exchange.setProperty(ERROR_CODE, errorCode);
        exchange.setProperty(ERROR_INFORMATION, errorInfo);
        exchange.setProperty(ERROR_DESCRIPTION, errorDesc);

    }
}
