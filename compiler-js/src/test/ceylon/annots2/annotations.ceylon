import check { check,fail,results }

import ceylon.language.meta{
  annotations
}
import ceylon.language.meta.declaration{
  ClassOrInterfaceDeclaration, ValueDeclaration,
  FunctionDeclaration
}

shared final annotation class AnnoTest1(text,count=1)
    satisfies OptionalAnnotation<AnnoTest1,ClassOrInterfaceDeclaration|ValueDeclaration|FunctionDeclaration>{
  shared String text;
  shared Integer count;
}
shared annotation AnnoTest1 annotest1(String text="") => AnnoTest1(text);
shared annotation AnnoTest1 annotest2(Integer count) => AnnoTest1("With Count", count);

shared final annotation class AnnoTest3(text)
    satisfies SequencedAnnotation<AnnoTest3,ClassOrInterfaceDeclaration|ValueDeclaration> {
  shared String text;
}
shared annotation AnnoTest3 annotest3(String text) => AnnoTest3(text);

shared annotest1 class Example1() {
  string => "Example1";
  shared annotest2(9) Integer printTime() {
    value m = system.milliseconds;
    print("printing at: ``m``");
    return m;
  }
}
annotest1("with something different")
annotest3("repeated twice")
annotest3("with different values")
shared class Example2() {
  annotest1{text="named call";}
  shared actual String string => "Example2";
}

annotest1("for an interface")
annotest3("for an interface")
interface Example3 {}

annotest3("one") annotest3("two") annotest3("three")
Singleton<String> testSingleton = Singleton("!");

shared final annotation class Issue235_1(shared String s, shared Issue235_2 b)
        satisfies OptionalAnnotation<Issue235_1,FunctionDeclaration> {
    string=>s+":"+b.string;
}

shared final annotation class Issue235_2(Integer i)
        satisfies OptionalAnnotation<Issue235_2,FunctionDeclaration> {
    string=>i.string;
} 

annotation Issue235_1 issue235_1() => Issue235_1("", Issue235_2(1));
annotation Issue235_1 issue235_2(Issue235_2 b=Issue235_2(2)) => Issue235_1("", b);

issue235_1 void testIssue235_1() {
}
issue235_2 void testIssue235_2() {
}

"This is an interface with a somewhat long documentation,
 longer than its path to the model would be anyway..."
interface TestShortLongDocs {
  "short"
  shared formal void a();
  "A doc long enough to make sure the path is used instead."
  shared formal void b();
  "short"
  shared void c() {}
  "Another doc long enough to make sure the path should be used instead of this text."
  shared void d() {}
}

"Issue 506"
object issue506{}

//annotest1("should be two")
annotest2{count=5;}
shared void test() {
  value a1 = annotations(`AnnoTest1`, `class Example1`);
  check(a1 exists, "Annotations 1 (opt on class)");
  value a2 = annotations(`AnnoTest3`, `class Example2`);
  check(a2.size == 2, "Annotations 2 (seq on class)");
  value a3 = annotations(`AnnoTest1`, `function test`);
  if (exists a3) {
    check(a3.count == 5, "Annotations 3 count");
    check(a3.text == "With Count", "Annotations 3 text");
  } else {
    fail("Annotations 3 (on toplevel method)");
  }
  check(annotations(`AnnoTest1`, `interface Example3`) exists, "Annotation on interface");
  
  value a4 = annotations(`AnnoTest3`, `value testSingleton`);
  if (nonempty a4) {
    check(a4.size==3, "Annotations 4 size");
  } else {
    fail("Annotations 4 (top-level attribute)");
  }
  check(!annotations(`AnnoTest1`, `value testSingleton`) exists, "testSingleton shouldn't have AnnoTest1");
  value a5 = annotations(`AnnoTest1`, `value Example2.string`);
  if (exists a5) {
    check(a5.text=="named call", "Annotations 5 text");
  } else {
    fail("Annotations 5 (on attribute)");
  }
  try {
  if (exists a6 = annotations(`AnnoTest1`, `function Example1.printTime`)) {
    check(a6.count == 9, "Annotations 6 count");
  } else {
    fail("Annotations 6 (on member method)");
  }
  } catch (Exception ex) {
    if ("lexical" in ex.message) {
      fail("member annotations don't work in lexical style");
    } else {
      fail("Annotations 6 ``ex``");
    }
  }
  value tiss235_1 = `function testIssue235_1`.annotations<Issue235_1>();
  value tiss235_2 = `function testIssue235_2`.annotations<Issue235_1>();
  if (nonempty tiss235_1) {
    check(tiss235_1.first.string == ":1", "Issue235_1 description - expected :1 got ``tiss235_1.first``");
  } else {
    fail("testIssue235_1 should have annotation Issue235_1");
  }
  if (nonempty tiss235_2) {
    check(tiss235_2.first.string == ":2", "Issue235_1 description - expected :2 got ``tiss235_2.first``");
  } else {
    fail("testIssue235_2 should have annotation Issue235_1");
  }
  "TestShortLongDocs has no doc annotations"
  assert(exists doc1 = `interface TestShortLongDocs`.annotations<DocAnnotation>().first);
  check("somewhat long" in doc1.description, "TestShortLongDocs doc is wrong: ``doc1.description``");
  try {
  "TestShortLongDocs.a has no doc annotations"
  assert(exists doc2 = `function TestShortLongDocs.a`.annotations<DocAnnotation>().first);
  check("short" == doc2.description, "TestShortLongDocs.a doc is wrong: ``doc2.description``");
  "TestShortLongDocs.b has no doc annotations"
  assert(exists doc3 = `function TestShortLongDocs.b`.annotations<DocAnnotation>().first);
  check("doc long enough to make sure" in doc3.description, "TestShortLongDocs.b doc is wrong: ``doc3.description``");
  "TestShortLongDocs.c has no doc annotations"
  assert(exists doc4 = `function TestShortLongDocs.c`.annotations<DocAnnotation>().first);
  check(doc4.description == doc2.description, "TestShortLongDocs.c doc is wrong: ``doc4.description``");
  "TestShortLongDocs.d has no doc annotations"
  assert(exists doc5 = `function TestShortLongDocs.d`.annotations<DocAnnotation>().first);
  check("doc long enough to make sure" in doc5.description, "TestShortLongDocs.d doc is wrong: ``doc5.description``");
  } catch (Exception e) {
    if ("lexical" in e.message) {
      fail("method tests won't work in lexical style");
    } else {
      throw e;
    }
  }
  check(!annotations(`DocAnnotation`, `class issue506`) exists, "#506.1 (anonymous class shouldn't have annotations)");
  check(annotations(`DocAnnotation`, `value issue506`) exists, "#506.2 (value should have annotations)");
  results();
}
