package quality.lang;
import org.junit.Assert;
import org.junit.Test;
import quality.util.EnvUtil;

public class SetEnvTest {
    @Test
    public void testJNISetEnv() throws InterruptedException {
        System.getenv("VAR123"); // Cache environment
        EnvUtil.setenv("VAR123","VAL123" , 1 );

        Assert.assertEquals("VAL123", EnvUtil.getenv("VAR123"));
        Assert.assertEquals(null, System.getenv("VAR123"));
    }
}
