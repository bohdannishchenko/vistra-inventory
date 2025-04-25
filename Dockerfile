# ---- Build Stage ----
  FROM gradle:4.7-jdk8 AS build
  WORKDIR /app
  
  # Copy source code
  COPY . .
  
  # Build the fat JAR (adjust task if needed)
  RUN gradle clean build -x test
  
  # ---- Runtime Stage ----
  FROM openjdk:8-jdk
  WORKDIR /app
  
  # Copy the fat jar from build stage
  COPY --from=build /app/build/libs/*.jar app.jar
  
  # Railway uses PORT env var
  ENV PORT=443
  EXPOSE 443
  
  # Start the app
  CMD ["java", "-jar", "app.jar", "-rest" ]