package com.ramphoenix.launcher;

import android.os.SystemClock;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise the actual public getters with a connected OBD sample, without Bluetooth. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class ObdReadingAccessTest {
    private ObdClient sample(Float pressure)throws Exception {
        ObdClient client=new ObdClient(RuntimeEnvironment.getApplication(),()->{});
        set(client,"connected",true);set(client,"publishedAt",SystemClock.elapsedRealtime());
        set(client,"oilPsi",pressure);set(client,"coolantC",70f);set(client,"oilTempC",80f);
        set(client,"transC",60f);set(client,"volts",12.5f);return client;
    }
    private void set(ObdClient client,String name,Object value)throws Exception {
        Field field=ObdClient.class.getDeclaredField(name);field.setAccessible(true);field.set(client,value);
    }
    @Test public void connectedPressureAccessReturnsWithoutRecursionAndClampsNegative()throws Exception {
        ObdClient client=sample(35f);assertEquals(35f,client.oilPsi(),0);assertEquals(35f,client.oilPsiClamped(),0);
        set(client,"oilPsi",-1f);assertEquals(0f,client.oilPsi(),0);assertEquals(0f,client.oilPsiClamped(),0);
        set(client,"oilPsi",null);assertNull(client.oilPsi());assertNull(client.oilPsiClamped());
    }
    @Test public void allScalarGettersWithholdExpiredAndDisconnectedSamples()throws Exception {
        ObdClient client=sample(35f);assertEquals(70f,client.coolantC(),0);assertEquals(80f,client.oilTempC(),0);
        assertEquals(60f,client.transC(),0);assertEquals(12.5f,client.volts(),0);
        set(client,"publishedAt",ReadingAge.NONE);assertMissing(client);
        set(client,"publishedAt",SystemClock.elapsedRealtime());set(client,"connected",false);assertMissing(client);
    }
    private void assertMissing(ObdClient client) {
        assertNull(client.oilPsi());assertNull(client.oilPsiClamped());assertNull(client.coolantC());
        assertNull(client.oilTempC());assertNull(client.transC());assertNull(client.volts());
    }
}
