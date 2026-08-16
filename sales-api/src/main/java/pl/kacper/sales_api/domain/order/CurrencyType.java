package pl.kacper.sales_api.domain.order;

public enum CurrencyType {

    PLN("pln");

    private final String value;

    CurrencyType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
