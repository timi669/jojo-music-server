# Vibe Music Server 🎶

## 介绍 📖

**Vibe Music Server** 是 Vibe Music 项目的后端 API 服务。本项目基于 **Spring Boot 3** 构建，采用 **Java 17**、**Maven**、**MyBatis-Plus**、**MySQL**、**Redis** 和 **MinIO** 等技术，为 Vibe Music 的客户端和管理端提供稳定、高效的数据支持和业务逻辑处理。

## 主要功能 ✨

本服务提供以下核心功能 API：

- **用户认证与管理**: 提供用户注册、登录、信息修改、头像上传、注销等接口，支持管理员对用户进行管理（查询、禁用/启用）。
- **内容管理**:
    - **歌手管理**: 添加、编辑、删除歌手信息。
    - **歌曲管理**: 添加、编辑、删除歌曲信息，处理歌曲文件上传。
    - **歌单管理**: 创建、编辑、删除歌单，管理歌单歌曲。
    - **轮播图管理**: 添加、编辑、删除首页轮播图。
- **用户互动**:
    - **评论管理**: 发表、查看、删除歌曲或歌单的评论。
    - **收藏管理**: 用户收藏/取消收藏歌曲、歌单。
    - **反馈管理**: 提交、查看、处理用户反馈。
- **文件服务**: 使用 MinIO 存储和管理音乐文件、图片（如头像、封面）等静态资源。
- **权限控制**: 基于 JWT 和角色进行 API 访问权限控制。
- **数据缓存**: 利用 Redis 缓存热点数据，提高访问速度。
- **邮件服务**: 支持发送验证码等邮件通知。

## 技术栈 🛠️

- **后端框架**: [Spring Boot 3](https://spring.io/projects/spring-boot)
- **开发语言**: [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- **构建工具**: [Maven](https://maven.apache.org/)
- **数据库**: [MySQL](https://www.mysql.com/) (推荐 8.0+)
- **ORM**: [MyBatis-Plus](https://baomidou.com/)
- **缓存**: [Redis](https://redis.io/)
- **对象存储**: [MinIO](https://min.io/)
- **认证**: [JWT (java-jwt)](https://github.com/auth0/java-jwt)
- **数据库连接池**: [Druid](https://github.com/alibaba/druid)
- **工具库**: Lombok, Spring Boot Validation, Java Mail

## 系统需求 ⚙️

- **JDK**: `17` 或更高版本
- **Maven**: `3.6` 或更高版本
- **MySQL**: `8.0` 或更高版本
- **Redis**: 推荐 `6.0` 或更高版本
- **MinIO**: 最新稳定版

## 代码仓库 ⭐

- [GitHub 代码仓库](https://github.com/Alex-LiSun/vibe-music-server.git)

## 文件下载 📥

本项目包含的所有文件，均已通过MinIO存储桶的形式进行分享，并提供百度网盘的下载链接。
- vibe-music-data: [https://pan.baidu.com/s/1IHU2EBodNmmjCeYi7_Tw5g?pwd=1234] (提取码: `1234`)

![vibe-music-data](https://github.com/Alex-LiSun/vibe-music-server/blob/main/img/vibe-music-data.png)
![vibe-music-data](https://github.com/Alex-LiSun/vibe-music-server/blob/main/img/vibe-music-data-songs.png)

## 安装与启动 🚀

1.  **克隆项目**

    ```bash
    # GitHub (示例)
    git clone https://github.com/Alex-LiSun/vibe-music-server.git

    cd vibe-music-server
    ```

2.  **环境准备**

    - 确保已安装并运行 **MySQL 8.0+** 数据库服务。
    - 创建名为 `vibe_music` 的数据库 (或与配置文件中名称一致)，并使用 `UTF-8` 字符集。
        ```sql
        CREATE DATABASE vibe_music CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        ```
    - 确保已安装并运行 **Redis** 服务。
    - 确保已安装并运行 **MinIO** 服务。
    - 在 MinIO 中创建一个名为 `vibe-music-data` 的 Bucket (或与配置文件中名称一致)，并确保服务具有读写权限。

3.  **配置应用**

    - 找到并修改 `src/main/resources/application.yml` 文件。
    - **数据库配置**: 修改 `spring.datasource` 下的 `url`, `username`, `password` 以匹配你的 MySQL 环境。
    - **Redis 配置**: 修改 `spring.data.redis` 下的 `host`, `port`, `password` (如果需要) 以匹配你的 Redis 环境。
    - **MinIO 配置**: 修改 `minio` 下的 `endpoint`, `accessKey`, `secretKey`, `bucket` 以匹配你的 MinIO 环境。
    - **邮件服务配置 (可选)**: 如果需要邮件功能（如验证码），修改 `spring.mail` 下的 `host`, `username`, `password`。 **注意：请勿将生产环境的敏感密码直接提交到代码库。** 建议使用环境变量或配置中心管理。

    ```yaml
    # src/main/resources/application.yml (部分示例)

    spring:
      datasource:
        url: jdbc:mysql://YOUR_MYSQL_HOST:3306/vibe_music?useUnicode=true&characterEncoding=utf-8&useSSL=false # 修改你的 MySQL 地址和库名
        username: YOUR_MYSQL_USER # 修改你的 MySQL 用户名
        password: YOUR_MYSQL_PASSWORD # 修改你的 MySQL 密码
        # ...

      data:
        redis:
          host: YOUR_REDIS_HOST # 修改你的 Redis 地址
          port: 6379
          password: YOUR_REDIS_PASSWORD # 如果 Redis 有密码，取消注释并修改
          database: 1
          # ...

      mail:
        host: smtp.example.com # 修改你的 SMTP 服务器地址
        username: your-email@example.com # 修改你的邮箱账号
        password: YOUR_EMAIL_APP_PASSWORD # 修改你的邮箱应用密码或授权码
        # ...

    minio:
      endpoint: http://YOUR_MINIO_HOST:9000 # 修改你的 MinIO 端点
      accessKey: YOUR_MINIO_ACCESS_KEY # 修改你的 MinIO Access Key
      secretKey: YOUR_MINIO_SECRET_KEY # 修改你的 MinIO Secret Key
      bucket: vibe-music-data # 确认 Bucket 名称与你创建的一致
    ```

4.  **构建项目** (使用 Maven)

    在项目根目录下执行：
    ```bash
    mvn clean package -DskipTests
    ```
    这将在 `target` 目录下生成一个可执行的 JAR 文件 (例如 `vibe-music-server-0.0.1-SNAPSHOT.jar`)。

5.  **运行服务**

    ```bash
    java -jar target/vibe-music-server-*.jar
    ```
    服务默认启动在 `8080` 端口 (Spring Boot 默认端口，可在 `application.yml` 中通过 `server.port` 修改)。

## 项目脚本 📜 (Maven)

- `mvn clean`: 清理构建产物。
- `mvn compile`: 编译项目源代码。
- `mvn test`: 运行单元测试。
- `mvn package`: 打包项目为可执行 JAR 文件。
- `mvn spring-boot:run`: 启动 Spring Boot 应用 (用于开发)。
- `java -jar target/*.jar`: 运行打包后的 JAR 文件。

## 项目演示 📺

视频地址：[https://www.bilibili.com/video/BV1tKJ8z8E6z/]

## API 文档 接口

本项目旨在为 [Vibe Music Client](https://github.com/Alex-LiSun/vibe-music-client) (客户端) 和 [Vibe Music Admin](https://github.com/Alex-LiSun/vibe-music-admin) (管理端) 提供后端支持。具体的 API 接口定义和使用方式，请参考项目源代码中的 Controller 层代码，或使用 API 文档工具 (如 Swagger，如果项目中集成了的话) 查看。

## 依赖服务说明 🔗

本项目运行依赖以下外部服务，请确保它们已正确安装、配置并正在运行：

- **MySQL**: 用于持久化存储核心业务数据。
- **Redis**: 用于数据缓存，提升性能。
- **MinIO**: 用于存储音乐文件、图片等静态资源。

## 免责声明 ⚠️

**Vibe Music Server** 项目仅供学习和技术研究使用。所有由本服务管理和存储的数据（包括用户信息、音乐文件、图片等）均由您自行配置和运行的 **MySQL**, **Redis**, **MinIO** 服务承载。请在遵守相关国家和地区的法律法规以及版权政策的前提下使用。

- **请勿用于任何商业用途。**
- 对于因使用本项目而可能产生的任何直接或间接问题、数据安全风险、版权纠纷或经济损失，项目作者不承担任何责任。
- 用户需自行承担所有使用风险，包括确保数据来源合法合规，以及所依赖服务的安全稳定运行。

在您部署和使用本软件前，请仔细阅读并理解本免责声明。继续使用即表示您同意本声明的所有条款。

## 许可证 📄

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 贡献 ❤️

欢迎各种形式的贡献，包括提交 Issue、Pull Request 或提出建议！

## 常见问题 (FAQ) ❓

- **启动失败，提示数据库连接错误？**
    - 检查 `application.yml` 中的 `spring.datasource` 配置是否正确 (URL、用户名、密码)。
    - 确认 MySQL 服务是否正在运行，并且网络可达。
    - 确认数据库 `vibe_music` 是否已创建，并且字符集正确。
    - 检查 MySQL 用户是否有权限访问该数据库。

- **启动失败，提示 Redis 连接错误？**
    - 检查 `application.yml` 中的 `spring.data.redis` 配置是否正确 (host, port, password)。
    - 确认 Redis 服务是否正在运行，并且网络可达。

- **文件上传失败或无法访问？**
    - 检查 `application.yml` 中的 `minio` 配置是否正确 (endpoint, accessKey, secretKey, bucket)。
    - 确认 MinIO 服务是否正在运行，并且网络可达。
    - 确认 MinIO 中名为 `vibe-music-data` (或你配置的名称) 的 Bucket 是否已创建。
    - 检查 MinIO 的 Bucket 策略或服务权限设置，确保应用有读写权限。
    - 检查 `spring.servlet.multipart` 的 `max-file-size` 和 `max-request-size` 是否足够大。

- **端口冲突 (Port already in use)？**
    - 检查是否有其他程序占用了 `8080` 端口 (或其他你在 `application.yml` 中配置的 `server.port`)。
    - 你可以修改 `application.yml` 中的 `server.port` 来使用其他端口。

- **如何查看 API 接口？**
    - 如果项目集成了 Swagger 或 SpringDoc，启动服务后通常可以通过访问 `/swagger-ui.html` 或 `/v3/api-docs` 来查看和测试 API。
    - 如果没有集成文档工具，需要直接查看 `src/main/java/.../controller` 目录下的 Java 代码来了解接口定义。