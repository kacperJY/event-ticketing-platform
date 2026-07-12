package pl.kacper.sales_api.common.utils;

import java.math.BigInteger;

public class PriceValueCalculator {

    public static BigInteger calculateZlotyToPennies(BigInteger zlotyValue){
        return (new BigInteger("100").multiply(zlotyValue));
    }
}
