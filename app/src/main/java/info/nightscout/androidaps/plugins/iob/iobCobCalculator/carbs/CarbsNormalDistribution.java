package info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs;

class CarbsNormalDistribution {
    double median = 0d; // In minutes
    double standardDeviation = 0d;
    double carbs = 0d;

    double minMedian = 5d; // In minutes
    double maxMedian = 600d; // In minutes

    CarbsNormalDistribution(double carbs, double minMedian, double maxMedian ) {
        this.carbs = carbs;
        this.minMedian = minMedian;
        this.maxMedian = maxMedian;

        // Very cautious defaults
        this.median = maxMedian;
        this.standardDeviation = maxMedian / 4d;
    }

    CarbsNormalDistribution(CarbsNormalDistribution other) {
        this.carbs = other.carbs;
        this.minMedian = other.minMedian;
        this.maxMedian = other.maxMedian;
        this.median = other.median;
        this.standardDeviation = other.standardDeviation;
    }

    public double get(long offset) {
        double variance = Math.pow(standardDeviation, 2);
        return carbs * ActiveCarbFromTriangles.tickSize * (1 / Math.sqrt(2 * Math.PI * variance)) * Math.pow(Math.E, -Math.pow(offset - median, 2) / (2 * variance));
    }
}
