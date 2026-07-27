package pl.kacper.sales_api.common.utils;

public class PriceValueCalculator {

    public static long calculateZlotyToPennies(long zlotyValue){
        return zlotyValue * 100;
    }
}
