# Use an official Maven image as the base image
FROM maven:3.9.8-eclipse-temurin-21 AS build
# Set the working directory in the container
WORKDIR /app
# Copy the pom.xml and the project files to the container
COPY pom.xml .
COPY package.json .
COPY package-lock.json .
COPY build.mjs .
COPY src ./src
# Build the application using Maven
RUN mvn -B clean package

# Use an official OpenJDK image as the base image
FROM eclipse-temurin:21-jre
# Set the working directory in the container
WORKDIR /app
# Copy the built JAR file from the previous stage to the container
COPY --from=build /app/target/keycloak-push-mfa-extension-simulator.jar .

EXPOSE 5005
# Set the command to run the application
CMD ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005", "-jar", "keycloak-push-mfa-extension-simulator.jar"]
