package rp.clientinspector;

import org.junit.jupiter.api.Test;
import rp.clientinspector.session.BrandNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrandNormalizerTest {
	@Test
	void loaderFromBrand() {
		assertEquals("fabric", BrandNormalizer.loader("fabric"));
		assertEquals("fabric", BrandNormalizer.loader("lunarclient:v2.23.12-2644,fabric"));
		assertEquals("fabric", BrandNormalizer.loader("lunarclient:d600f33/dev,fabric"));
		assertEquals("vanilla", BrandNormalizer.loader("vanilla"));
		assertEquals("forge", BrandNormalizer.loader("forge"));
		assertEquals("neoforge", BrandNormalizer.loader("neoforge"));
		assertEquals("quilt", BrandNormalizer.loader("quilt"));
		assertEquals("other", BrandNormalizer.loader("lunarclient:v2.21.33-2612"));
		assertEquals("none", BrandNormalizer.loader(null));
		assertEquals("none", BrandNormalizer.loader("  "));
	}

	@Test
	void baseStripsLoaderSuffixOnly() {
		assertEquals("lunarclient:v2.23.12-2644", BrandNormalizer.base("lunarclient:v2.23.12-2644,fabric"));
		assertEquals("fabric", BrandNormalizer.base("fabric"));
		assertEquals("a,b", BrandNormalizer.base("a,b"));
		assertNull(BrandNormalizer.base(null));
	}

	@Test
	void velocitySuffixIsRemoved() {
		assertEquals("fabric", BrandNormalizer.clean("fabric (Velocity)"));
		assertEquals("fabric", BrandNormalizer.loader("fabric (Velocity)"));
	}
}
