package ucar.nc2.ogc2.om;

import net.opengis.gml.x32.TimePeriodType;
import net.opengis.om.x20.TimeObjectPropertyType;
import ucar.nc2.ft.StationTimeSeriesFeature;
import ucar.nc2.ogc2.gml.NC_TimePeriodType;

/**
 * Created by cwardgar on 3/6/14.
 */
public abstract class NC_TimeObjectPropertyType {
    // wml2:Collection/wml2:observationMember/om:OM_Observation/om:phenomenonTime
    public static TimeObjectPropertyType createPhenomenonTime(StationTimeSeriesFeature stationFeat) {
        TimeObjectPropertyType phenomenonTime = TimeObjectPropertyType.Factory.newInstance();

        // gml:TimePeriod
        TimePeriodType timePeriod = NC_TimePeriodType.createTimePeriod(stationFeat);
        phenomenonTime.setAbstractTimeObject(timePeriod);

        return phenomenonTime;
    }

    private NC_TimeObjectPropertyType() { }
}
