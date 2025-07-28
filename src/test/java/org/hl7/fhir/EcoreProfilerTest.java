package org.hl7.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.hl7.fhir.EcoreProfiler;
import org.hl7.fhir.ElementDefinition;
import org.hl7.fhir.StructureDefinition;
import org.hl7.fhir.StructureDefinitionSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kohsuke.args4j.CmdLineException;

public class EcoreProfilerTest {

	static EcoreProfiler sut;
	static EObject profile;
	static EObject spec;

	@BeforeAll
	static void beforAll() {
		String[] ss = {"-p", "StructureDefinition-qicore-adverseevent.xml", "-i", "fhir.ecore", "-o", "out.ecore"};
		try {
			sut = new EcoreProfiler(ss);
		} catch (CmdLineException e) {
			e.printStackTrace();
		}
		// InputStream readerProfile = AHRQProfilerTest.class.getClassLoader().getResourceAsStream("StructureDefinition-de-identified-uds-plus-patient.xml");
		// assertNotNull(readerProfile);
		// profile = FHIRSerDeser.load(readerProfile, Finals.SDS_FORMAT.XML);
		// assertNotNull(profile);
		// assertTrue(profile instanceof StructureDefinition);
		// InputStream readerSpec = AHRQProfilerTest.class.getClassLoader().getResourceAsStream("fhir.ecore");
		// assertNotNull(readerSpec);
		// spec = FHIRSerDeser.load(readerSpec, Finals.SDS_FORMAT.ECORE);
		// assertNotNull(spec);
	}

	//@Test
	void testRun() {
		System.out.println(profile.eClass().getName());
//		System.out.println(spec.eClass().getName());
		assertTrue(true);
	}

	@Test 
	void testLoadProfile() {
		StructureDefinition profile = sut.loadProfile();
		assertNotNull(profile);
	}

	@Test 
	void testLoadSpec() {
		EPackage spec = sut.loadSpec();
		assertNotNull(spec);
	}
	@Test
	void testPopulateEcoreOut() {
		StructureDefinition profile = sut.loadProfile();
		EPackage spec = sut.loadSpec();
		EPackage out = sut.copySpec(spec);
		sut.clearClassifiers(out);
		assertEquals(0, out.getEClassifiers().size());

		StructureDefinitionSnapshot snap = profile.getSnapshot();
		sut.populateEcoreOut(snap, spec, out);

	}

	@Test
	void testApplySnapshotElementToFeature() {
		StructureDefinition profile = sut.loadProfile();
		EPackage spec = sut.loadSpec();
		EPackage out = sut.copySpec(spec);
		StructureDefinitionSnapshot snap = profile.getSnapshot();
		sut.clearClassifiers(out);
		assertEquals(0, out.getEClassifiers().size());


		EStructuralFeature actuality = null;
		EClassifier classifier = spec.getEClassifier("AdverseEvent");
		if (classifier instanceof EClass adverseEventClass) {
			actuality = adverseEventClass.getEStructuralFeature("actuality");
			if (actuality != null) {
				System.out.println("Found feature: " + actuality.getName());
			} else {
				System.err.println("❌ Feature 'actuality' not found in AdverseEvent");
			}
		} else {
			System.err.println("❌ EClass 'AdverseEvent' not found in EPackage");
		}


		ElementDefinition ed = null;
		for (ElementDefinition ed1 : snap.getElement()) {
			if ("AdverseEvent.actuality".equals(ed1.getPath().getValue())) {
				ed = ed1;
				break;
			} else {
				continue;
			}
		}
		assertNotNull(ed);
		
		sut.applySnapshotElementToFeature(ed, actuality);
		assertEquals(1, actuality.getLowerBound());
		assertEquals(1, actuality.getUpperBound());
	}

	
}
