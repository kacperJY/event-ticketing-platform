package pl.kacper.sales_api.common.utils;

import java.math.BigInteger;

public class PriceValueCalculator {

    public static long calculateZlotyToPennies(long zlotyValue){
        return zlotyValue * 100;
    }
}
