package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class InferenceSettingsTest {
    @Test public void safeDefaultsAndSupportedCustomLimitsAreAvailable(){
        assertEquals(4096,InferenceSettings.DEFAULT.contextTokens);
        assertEquals(4,InferenceSettings.DEFAULT.threads);assertEquals(512,InferenceSettings.DEFAULT.maxTokens);
        assertEquals("Context 2048 • Threads 2 • Max 128",new InferenceSettings(2048,2,128).summary());
        new InferenceSettings(8192,8,2048);new InferenceSettings(512,1,1);
    }
    @Test public void malformedAndUnsafeLimitsAreRejected(){
        int[][] invalid={{0,4,128},{513,4,128},{8448,4,128},{2048,0,128},{2048,9,128},
                {2048,4,0},{4096,4,2049},{512,1,512},{-1,-1,-1}};
        for(int[] n:invalid)try{new InferenceSettings(n[0],n[1],n[2]);fail("Unsafe limits accepted");}
        catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}
    }
}
