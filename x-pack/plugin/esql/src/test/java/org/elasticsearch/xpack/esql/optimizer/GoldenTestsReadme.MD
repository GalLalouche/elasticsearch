# Golden tests

## What are golden tests?

Golden tests, or [characterization tests](https://en.wikipedia.org/wiki/Characterization_test), are
tests which record the current behavior of a system, and then verify the behavior has not changed.
Contrast with regular (unit) tests, which include a specific assertion of some computed value.

## How to I add a new golden test?

Just create a new test, and invoke `doTest`. For example:

```java
public void testBasic() throws Exception {
  doTest("FROM employees");
}
```

The test runner will pick up the "orphan" test and create the required outputs (see next section).
You can also specify which plans you want to verify (all plans are output by default):

```java
public void testBasic() throws Exception {
  doTest("FROM employees", EnumSet.of(Stage.ANALYZER, Stage.LOGICAL));
}
```

Golden test outputs are stored in the
`x-pack/plugin/esql/src/test/resources/org/elasticsearch/xpack/esql/optimizer/golden_tests/`
directory.
Each golden test is a directory containing the plan outputs for a specific test query.

## What happens when a golden test fails?

When a golden test fails, the test runner will output the diff between the expected and actual to
the terminal.

```
--- Actual
+++ Expected
@@ -1,1 +1,1 @@
- Limit[Literal[1000,INTEGER],x]
+ Limit[Literal[1000,INTEGER],y]
```

It will also create a markdown file containing a table of the differences:

| Line # | Actual                         | Expected                       |
|--------|--------------------------------|--------------------------------|
| 0      | Limit[Literal[1000,INTEGER],x] | Limit[Literal[1000,INTEGER],y] |

As well as output the actual plan to a file with a `.actual` suffix, so you can use whatever `diff`
tool you wish:

```console
$ cat basic.expected
Limit[Literal[1000,INTEGER],y]
$ cat basic.actual
Limit[Literal[1000,INTEGER],x]
$ diff basic.actual basic.expected
1c1
< Limit[Literal[1000,INTEGER],x]
---
> Limit[Literal[1000,INTEGER],y]
```

## How do I bulldoze an existing output?

Of course, sometimes you *want* the output to change.
Use `-Dgolden.mode=bulldoze` to **bulldoze** (or overwrite) all the existing outputs.
You can use this in combination with `-Dtests.method` to bulldoze specific tests.

## Drawbacks of golden tests

Golden tests are by their very nature much noisier than regular tests:
They compare the entire output of the system, rather than a specific value. For example, you might
change the name of a local variable, only to change it later down the plan. This change has no
effect on the final outcome, nor does it incur any runtime cost, but it will cause the golden test
to fail nonetheless.

One should feel comfortable bulldozing failed tests if one expects changes in the underlying tree.
Golden tests should be the *first* line of defense, not the only one!

