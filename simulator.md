The project is to implement a simulator for ES|QL + property tests. This is split into several subtasks:
1. The property tests themselves would be run with jqwik. So both the generator and shrinker should be written within that framework. However, ideally, the actual generating and shrinking code would be extracted to its own module so it won't be overly-coupled to jqwik.
1. Creating a generator for an unoptimized ES|QL tree (the same thing you get after parsing). The relevant class here is LogicalPlan. We'll start with generating just 3 simple commands: KEEP, DROP, FROM.
1. Creating a shrinker for an unoptimized ES|QL tree. We'll start with the most basic of shrinking (removing the top level node(s)).
1. Creating a generator for data. We'll start with very basic data, say with 2 int columns named x and y. Later we'll making everything generatable.
1. Similarly, creating a shrinker for data.
1. Creating an ES|QL simulator, which given the data and the plan will run the plan on the data. Unlike real ES|QL, this will use very basic for loops for each ES|QL command/operator.
1. Finally: generating a plan, sending it to both the simulator and the actual elastic server, and comparing the results. If the results are the same, the test passes. If they differ, it should be shrunk via jqwik (it should already handle that logic if everything is setup correctly).


Do note there is some code already esql/plan/simulator, though I'm not sure how good it is and how much of it works.
