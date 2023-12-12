package org.mifos.connector.ams.paygops.paygopsDTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 {
 "transaction_id": "A1234B5678",
 "amount": 12345.53,
 "wallet_name": "M JOHN DOE",
 "wallet_msisdn": "+25512341234",
 "sent_datetime": "2022-03-15T09:56:03.637552",
 "memo": "C12345",
 "wallet_operator": "MPESA",
 "country": "TZ",
 "currency": "TZS"

 }
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaygopsRequestDTO {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("amount")
    private Long amount;

    @JsonProperty("wallet_name")
    private String walletName;

    @JsonProperty("wallet_msisdn")
    private String phoneNumber;

    @JsonProperty("memo")
    private String memo;

    @JsonProperty("wallet_operator")
    private String operator;

    @JsonProperty("country")
    private String country;

    @JsonProperty("currency")
    private String currency;

    @Override
    public String toString() {
        return "PaygopsRequestDTO{" +
                "transaction_id='" + transactionId + '\'' +
                ", amount='" + amount + '\'' +
                ", wallet_name='" + walletName + '\'' +
                ", wallet_msisdn='" + phoneNumber + '\'' +
                ", memo='" + memo + '\'' +
                ", walletOperator=" + operator +
                ", country='" + country + '\'' +
                ", currency='" + currency + '\'' +
                '}';
    }

    public void setPhoneNumber(String phoneNumber) {
        if (isValidPhoneNumber(phoneNumber)) {
            this.phoneNumber = phoneNumber.trim();
        }
    }

    /**
     * Checks if a phone number is valid.
     * @param phoneNumber the phone number
     * @return true if the phone number is valid
     */
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return false;
        String number = phoneNumber.trim();
        if (number.length() == 0) return false;
        try {
            String fullPhoneNumber = number.startsWith("+") ? number : "+" + number;
            PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
            Phonenumber.PhoneNumber phone = phoneUtil.parse(fullPhoneNumber, Phonenumber.PhoneNumber.CountryCodeSource.UNSPECIFIED.name());
            return phoneUtil.isValidNumber(phone);
        } catch (NumberParseException e) {
            return false;
        }
    }
}
