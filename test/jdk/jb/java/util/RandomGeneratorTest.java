import java.util.Comparator;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/* @test
 * @summary regression test on JBR-7456, it checks if each available implementations of all random number generator
 *          algorithm can be instantiated including the deafult one.
 *          In jbr17, jbr21 the module `jdk.random`, that was not included in the JBR runtime binaries, contains
 *          implementations of the random number generator algorithm including "L32X64MixRandom", which is default.
 *          So it is expected that the test fails on the runtime because the default algorithm is not available. It
 *          should pass on the JBRSDK binaries.
 *          In jdk23 the `jdk.random` module has been removed from the JDK. All algorithm implementations have been
 *          moved to the `java.base` module. So the test is valid for both kinds of binaries.
 *
 * @run main/othervm RandomGeneratorTest
 */

public static void main(final String[] args) throws Exception {

    System.out.println("testing all available RandomGenerator factories:");
    RandomGeneratorFactory.all()
            .forEach(RandomGeneratorTest::testRandomGenerator);

    System.out.println();

    if (isFailed)
        throw new RuntimeException(errMsg);

    if (!wasDefaultTested)
        throw new RuntimeException("the default RandomGenerator factory is not available");
}

static void testRandomGenerator(RandomGeneratorFactory<RandomGenerator> factory) {
    try {
        if (!wasDefaultTested) {
            RandomGeneratorFactory<RandomGenerator> defaultFactory = RandomGeneratorFactory.getDefault();
            wasDefaultTested = (defaultFactory.group().compareTo(factory.group()) == 0)
                    && (defaultFactory.name().compareTo(factory.name()) == 0);
        }

        RandomGenerator randomGenerator = factory.create();System.out.printf("%s %s - ",
                factory.group(),
                factory.name());

        Random.from(randomGenerator);
        System.out.println("passed");

    } catch (IllegalArgumentException e) {
        errMsg = e.getMessage();
        isFailed = true;
    }
}
}