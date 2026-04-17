# Tinyflow-java

## 快速开始

引入依赖

```xml
<dependency>
    <groupId>dev.tinyflow</groupId>
    <artifactId>tinyflow-java-core</artifactId>
    <version>2.0.2</version>
</dependency>
```

初始化 Tinyflow

```java
String flowDataJson = "从前端传递的流程数据";
Tinyflow tinyflow = new Tinyflow(flowDataJson);

Map<String, Object> variables = new HashMap<>();
variables.put("name", "张三");
variables.put("age", 18);

tinyflow.execute(variables);
```
