

## 项目简介

novel 是一套基于时下最新 Java 技术栈 `Spring Boot 3` + `Vue 3` 开发的前后端分离学习型小说项目，由小说门户系统、作家后台管理系统、平台后台管理系统等多个子系统构成。当前真实实现的主线功能：注册/登录（图形验证码 + JWT）、首页推荐与榜单、小说详情/目录/章节阅读、分类与评论、Elasticsearch 检索、作家入驻与发布作品/章节、新闻公告、图片上传。

novel-cloud 是 novel 项目的微服务版本，基于 `Spring Cloud 2022` & `Spring Cloud Alibaba 2022` 构建，数据结构、后端接口和 novel 项目保持完全一致，Vue 3 开发的前端能无缝对接这两个项目。


## 开发环境

- MySQL 8.0
- Redis 7.0
- Elasticsearch 8.6.2
- RabbitMQ 3.x
- XXL-JOB 2.3.1
- Nacos 2.2.1
- JDK 17
- Maven 3.8
- IntelliJ IDEA 2021.3（可选）
- Node 16.14

## 后端技术选型

| 技术                   |       版本       | 说明                                | 官网                               |                                                              学习                                                               |
|----------------------|:--------------:|-----------------------------------| -------------------------------------- |:-----------------------------------------------------------------------------------------------------------------------------:|
| Spring Cloud         |    2022.0.1    | 微服务开发的一站式解决方案                     | [进入](https://spring.io/projects/spring-cloud) |                              [进入](https://docs.spring.io/spring-cloud/docs/current/reference/html/)                               |
| Spring Cloud Alibaba | 2022.0.0.0-RC1 | 阿里巴巴贡献的 Spring Cloud 微服务开发一站式解决方案 | [进入](https://github.com/alibaba/spring-cloud-alibaba) |                              [进入](https://spring-cloud-alibaba-group.github.io/github-pages/2021/zh-cn/2021.0.5.0/index.html)                               |
| Nacos                |     2.2.1      | 服务发现和配置管理                         | [进入](https://nacos.io)  |                              [进入](https://nacos.io/zh-cn/docs/what-is-nacos.html)                               |
| Spring Boot          |     3.0.5      | 容器 + MVC 框架                       | [进入](https://spring.io/projects/spring-boot) |                              [进入](https://docs.spring.io/spring-boot/docs/3.0.0/reference/html)                               |
| MyBatis              |     3.5.9      | ORM 框架                            | [进入](http://www.mybatis.org)                |                                       [进入](https://mybatis.org/mybatis-3/zh/index.html)                                       |
| MyBatis-Plus         |     3.5.3      | MyBatis 增强工具                      | [进入](https://baomidou.com/)                 |                                           [进入](https://baomidou.com/pages/24112f/)                                            |
| JJWT                 |     0.11.5     | JWT 登录支持                          | [进入](https://github.com/jwtk/jjwt)          |                                                               -                                                               |
| Lombok               |    1.18.24     | 简化对象封装工具                          | [进入](https://github.com/projectlombok/lombok) |                                         [进入](https://projectlombok.org/features/all)                                          |
| Caffeine             |     3.1.0      | 本地缓存支持                            | [进入](https://github.com/ben-manes/caffeine)              |                                  [进入](https://github.com/ben-manes/caffeine/wiki/Home-zh-CN)                                  |
| Redis                |      7.0       | 分布式缓存支持                           | [进入](https://redis.io)                 |                                                  [进入](https://redis.io/docs)                                                  |
| Redisson             |     3.17.4     | 分布式锁实现                            | [进入](https://github.com/redisson/redisson)                 |                              [进入](https://github.com/redisson/redisson/wiki/%E7%9B%AE%E5%BD%95)                               |
| RabbitMQ             |      3.x       | 开源消息中间件                           | [进入](https://www.rabbitmq.com)                 |                                [进入](https://www.rabbitmq.com/tutorials/tutorial-one-java.html)                                |
| MySQL                |      8.0       | 数据库服务                             | [进入](https://www.mysql.com)                 |                       [进入](https://docs.oracle.com/en-us/iaas/mysql-database/doc/getting-started.html)                        |
| Elasticsearch        |     8.6.2      | 搜索引擎服务                            | [进入](https://www.elastic.co)                 |                       [进入](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html)                        |
| XXL-JOB              |     2.3.1      | 分布式任务调度平台                         | [进入](https://www.xuxueli.com/xxl-job)                 |                                             [进入](https://www.xuxueli.com/xxl-job)                                             |
| Springdoc-openapi    |     2.0.0      | Swagger 3 接口文档自动生成                | [进入](https://github.com/springdoc/springdoc-openapi)                |                                                 [进入](https://springdoc.org/)                                                  |
| Undertow             |  2.2.17.Final  | Java 开发的高性能 Web 服务器               | [进入](https://undertow.io) |                                         [进入](https://undertow.io/documentation.html)                                          |

> 技术选型原则：**只保留真实生效、能讲清取舍的组件**。
> 已下线的 Spring Boot Admin 监控中心、ShardingSphere-JDBC 分表配置均属于"接了但没跑起来"的摆设，
> 具体原因与面试回答口径见 [doc/面试讲解.md](doc/面试讲解.md)。

## 前端技术选型

| 技术               |  版本   | 说明                       | 官网                             |                        学习                         |
| :----------------- | :-----: | -------------------------- |--------------------------------| :-------------------------------------------------: |
| Vue.js        |  3.2.13  | 渐进式 JavaScript 框架 | [进入](https://vuejs.org)        |   [进入](https://staging-cn.vuejs.org/guide/introduction.html)    |
| Vue Router            |  4.0.15  | Vue.js 的官方路由                    | [进入](https://router.vuejs.org) | [进入](https://router.vuejs.org/zh/guide/) |
| axios       |  0.27.2  | 基于 promise 的网络请求库               | [进入](https://axios-http.com)   |     [进入](https://axios-http.com/zh/docs/intro)      |
| element-plus               | 2.2.0  | 基于 Vue 3，面向设计师和开发者的组件库   | [进入](https://element-plus.org) |   [进入](https://element-plus.org/zh-CN/guide/design.html)   |

## 软件架构

![QQ20200520-215756](https://s3.ax1x.com/2020/12/09/r92rrT.png)

👉 [克隆架构图](https://www.processon.com/view/5fd028fbe0b34d425254e710)

## 项目结构

```
novel-cloud
├── novel-core            -- 公共能力（被所有业务服务依赖）
│   ├── novel-common      -- 常量 / DTO / RestResp / JwtUtils / UserHolder / 消息体
│   └── novel-config      -- 缓存 / 分布式锁 / 全局异常 / XSS / 公共 yml
├── novel-gateway  :8888  -- 路由 + 跨域（只路由，不鉴权）
├── novel-home     :9001  -- 首页推荐位、友情链接
├── novel-author   :9010  -- 作家入驻、发布作品/章节（Feign 转发给 book）
├── novel-book     :9020  -- 【核心】书/章节/正文/评论、发布写链路、MQ 生产与缓存消费
├── novel-news     :9030  -- 新闻公告
├── novel-resource :9040  -- 图形验证码、图片上传/读取
├── novel-search   :9050  -- ES 检索、ES 增量消费、死信消费、XXL-JOB 全量同步
└── novel-user     :9060  -- 注册/登录、资料、反馈、内部批量查用户
```

> 本次优化下线了 `novel-monitor`（Spring Boot Admin 监控中心）、ShardingSphere-JDBC
> 分表配置、以及一批零调用的遗留实体与"示例"消费者。原因与面试回答口径见
> [doc/面试讲解.md](doc/面试讲解.md)。

## 面试与求职材料

| 文档 | 内容 |
|---|---|
| [doc/面试讲解.md](doc/面试讲解.md) | **项目详细描述**：业务域模型、14 张表、48 个接口、8 条业务链路逐步拆解、数据流转全景、明确未实现的业务边界 |
| [doc/面试考点与坑.md](doc/面试考点与坑.md) | **技术与坑**：按 9 个技术域整理考点、40 条坑速查表、连环追问模拟、简历避雷词、主动坦白清单 |
| [doc/简历写法.md](doc/简历写法.md) | **可直接复制的简历文本**：4 个版本、技术栈模板、措辞避雷对照、自检表、60 秒 / 3 分钟口播稿 |

三份文档互为索引，建议阅读顺序：项目详解 → 考点与坑 → 简历写法。

## 项目演示

[https://www.bilibili.com/video/BV1Xa4y1T7CF](https://www.bilibili.com/video/BV1Xa4y1T7CF/)

## 捐赠支持

开源项目不易，若此项目能得到你的青睐，可以捐赠支持作者持续开发与维护。

![mini-code](https://s1.ax1x.com/2020/10/31/BUQJwq.png)

