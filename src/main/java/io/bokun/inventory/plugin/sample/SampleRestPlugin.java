package io.bokun.inventory.plugin.sample;

import java.io.*;
import java.net.HttpURLConnection;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.net.URL;
import java.security.InvalidKeyException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;

import javax.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import javax.json.JsonValue;

import com.google.common.collect.*;
import com.google.gson.*;
import com.google.gson.stream.JsonToken;
import com.google.inject.*;
import com.squareup.okhttp.*;
import io.bokun.inventory.plugin.api.rest.*;
import io.bokun.inventory.plugin.api.rest.Duration;
import io.bokun.inventory.plugin.api.rest.Address;
import io.undertow.server.*;

import org.apache.commons.lang3.ObjectUtils.Null;
import org.slf4j.*;

import static io.bokun.inventory.plugin.api.rest.PluginCapability.*;
import static io.undertow.util.Headers.*;
import static java.util.concurrent.TimeUnit.*;


/**
 * The actual Inventory Service API implementation.
 *
 * @author Mindaugas Žakšauskas
 */
public class SampleRestPlugin {

    private static final Logger log = LoggerFactory.getLogger(SampleRestPlugin.class);

    /**
     * Default OkHttp read timeout: how long to wait (in seconds) for the backend to respond to requests.
     */
    private static final long DEFAULT_READ_TIMEOUT = 30L;

    // private static final String ORG_PID = "982127";
    // private static final String TARGET_PID = "1016497";
    // private static final String VENDOR_ID = "98806";

    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
    private static final String LOG_FILE = "bokun_plugin_errors.log";

    private static final DateTimeFormatter UTC_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OkHttpClient client;

    @Inject
    public SampleRestPlugin() {
        this.client = new OkHttpClient();
        client.setReadTimeout(DEFAULT_READ_TIMEOUT, SECONDS);
    }

    // helper method to express string as required string parameter structure, required by the REST API
    private PluginConfigurationParameter asRequiredStringParameter(String name) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.STRING);
        param.setRequired(false);
        return param;
    }

    // helper method to express string as required long parameter structure, required by the REST API
    private PluginConfigurationParameter asRequiredLongParameter(String name) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.LONG);
        param.setRequired(true);
        return param;
    }

    // helper method to express string as required string parameter structure, required by the REST API
    private PluginConfigurationParameter asStringParameter(String name, Boolean required) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.STRING);
        param.setRequired(required);
        return param;
    }

    // helper method to express string as required long parameter structure, required by the REST API
    private PluginConfigurationParameter asLongParameter(String name, Boolean required) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.LONG);
        param.setRequired(required);
        return param;
    }

    /**
     * Responds to <tt>/plugin/definition</tt> by sending back simple plugin definition JSON object.
     */
    public void getDefinition(@Nonnull HttpServerExchange exchange) {
        PluginDefinition definition = new PluginDefinition();
        definition.setName(Configuration.getPluginName());
        definition.setDescription(Configuration.getPluginDescription());

        definition.getCapabilities().add(AVAILABILITY);

        // below entry should be commented out if the plugin only supports reservation & confirmation as a single step
        // definition.getCapabilities().add(RESERVATIONS);
        // definition.getCapabilities().add(RESERVATION_CANCELLATION);
        // definition.getCapabilities().add(AMENDMENT);

        // reuse parameter names from grpc
        // definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_SCHEME, false));    // e.g. https
        // definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_HOST, false));      // e.g. your-api.your-company.com
        // definition.getParameters().add(asLongParameter(Configuration.VISTRA_API_PORT, false));        // e.g. 443
        // definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_PATH, false));      // e.g. /api/1
        // definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_USERNAME, false));
        // definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_PASSWORD, false));
        // definition.getParameters().add(asLongParameter(Configuration.VISTRA_API_EXTERNAL_PID, false));
        definition.getParameters().add(asStringParameter(Configuration.VISTRA_API_EXTERNAL_PIDS, true));

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(definition));
    }

    /**
     * This method should list all your products
     */
    // Enhanced error logging
    private void logError(String message, Throwable throwable) {
        String errorId = generateErrorId();
        String timestamp = LocalDateTime.now().format(UTC_DATE_FORMATTER);
        String stackTrace = getStackTraceAsString(throwable);

        // Console logging
        System.err.printf("[%s] [%s] %s\n%s\n",
            timestamp, errorId, message, stackTrace);

        // File logging
        try {
            Files.write(
                Paths.get(LOG_FILE),
                String.format(
                    "---- [%s] [%s] ----\n" +
                    "Message: %s\n" +
                    "Exception: %s\n" +
                    "Stack Trace:\n%s\n\n",
                    timestamp,
                    errorId,
                    message,
                    throwable.toString(),
                    stackTrace
                ).getBytes(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ioException) {
            System.err.println("Failed to write to error log: " + ioException.getMessage());
        }
    }

    private String generateErrorId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private HttpURLConnection createHttpConnection(String method, String endpoint) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        // Build full URL
        String fullUrl = Configuration.getBokunApiBaseUrl() + endpoint;

        // Create connection
        HttpURLConnection connection = (HttpURLConnection) new URL(fullUrl).openConnection();

        // Generate authentication headers
        String date = Instant.now().atOffset(ZoneOffset.UTC).format(UTC_DATE_FORMATTER);
        String signature = generateSignature(
            method,
            endpoint,
            date
        );

        // Set request properties
        connection.setRequestMethod(method);
        connection.setRequestProperty("X-Bokun-Date", date);
        connection.setRequestProperty("X-Bokun-AccessKey", Configuration.getBokunAccessKey());
        connection.setRequestProperty("X-Bokun-Signature", signature);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");

        // Only set DoOutput for methods that send request bodies
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            connection.setDoOutput(true);
        }

        return connection;
    }

    // Enhanced API error handling
    private void handleApiError(HttpURLConnection connection) throws IOException {
        String errorResponse = "No error details available";
        String errorId = generateErrorId();

        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream != null) {
                errorResponse = readStream(errorStream);
            }
        } catch (Exception e) {
            logError("Failed to read error stream", e);
        }

        String errorMessage = String.format(
            "Bokun API Error [%s]\n" +
            "HTTP Status: %d\n" +
            "Response: %s",
            errorId,
            connection.getResponseCode(),
            errorResponse
        );

        logError(errorMessage, new RuntimeException("Logged Bokun API error: " + errorId));
        throw new RuntimeException("API_ERROR_" + errorId);
    }

    public static String generateSignature(
        String httpMethod,
        String apiPath,
        String date
    ) throws NoSuchAlgorithmException, InvalidKeyException {

        // 2. Create the message to sign
        String message = date + Configuration.getBokunAccessKey() + httpMethod + apiPath;

        System.out.println(httpMethod + " " + apiPath);

        // 3. Create HMAC-SHA1 key
        SecretKeySpec signingKey = new SecretKeySpec(
            Configuration.getBokunSecretKey().getBytes(StandardCharsets.UTF_8),
            HMAC_SHA1_ALGORITHM
        );

        // 4. Calculate HMAC-SHA1 signature
        Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
        mac.init(signingKey);
        byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

        // 5. Base64 encode the result
        return Base64.getEncoder().encodeToString(rawHmac);
    }

    public void searchProducts(@Nonnull HttpServerExchange exchange) {
        log.trace("In ::searchProducts");

        try {
            // 1. Parse request
            SearchProductRequest request = new Gson().fromJson(
                new InputStreamReader(exchange.getInputStream(), StandardCharsets.UTF_8),
                SearchProductRequest.class
            );

            Configuration configuration = Configuration.fromRestParameters(request.getParameters());

            String[] externalIds = configuration.externalIds;
            if (externalIds == null || externalIds.length == 0) {
                throw new IllegalArgumentException("No externalIds provided in configuration.");
            }

            List<BasicProductInfo> products = new ArrayList<>();

            for (String externalId : externalIds) {
                StringBuilder pathBuilder = new StringBuilder("/activity.json/").append(externalId);

                HttpURLConnection connection = createHttpConnection("GET", pathBuilder.toString());

                try {
                    if (connection.getResponseCode() == 200) {
                        ProductDescription product = parseProductDescription(connection.getInputStream());

                        BasicProductInfo basicProductInfo = new BasicProductInfo();
                        basicProductInfo.setId(product.getId());
                        basicProductInfo.setName(product.getName());
                        basicProductInfo.setDescription(product.getDescription());
                        basicProductInfo.setPricingCategories(product.getPricingCategories());
                        basicProductInfo.setCountries(product.getCountries());
                        basicProductInfo.setCities(product.getCities());

                        products.add(basicProductInfo);
                    } else {
                        log.warn("Non-200 response for externalId {}: {}", externalId, connection.getResponseCode());
                    }
                } finally {
                    connection.disconnect();
                }
            }

            exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(new Gson().toJson(products));

        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logError("Error while searching products: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        }

        log.trace("Out ::searchProducts");
    }


    private List<BasicProductInfo> parseResponse(InputStream inputStream) {
        List<BasicProductInfo> products = new ArrayList<>();

        try (JsonReader reader = Json.createReader(inputStream)) {
            JsonObject response = reader.readObject();
            JsonArray items = response.getJsonArray("items");

            for (JsonValue item : items) {
                JsonObject activity = (JsonObject) item;
                BasicProductInfo product = new BasicProductInfo();

                // Required fields
                product.setId(activity.getString("id"));
                product.setName(activity.getString("title"));
                product.setDescription(activity.getString("summary", ""));

                // Pricing categories (default to Adult/Child if none specified)
                product.setPricingCategories(new ArrayList<>());
                PricingCategory adult = new PricingCategory();
                adult.setId("ADULT");
                adult.setLabel("Adult");

                PricingCategory child = new PricingCategory();
                child.setId("CHILD");
                child.setLabel("Child");

                product.getPricingCategories().add(adult);
                product.getPricingCategories().add(child);

                // Location information
                List<String> cities = new ArrayList<>();
                List<String> countries = new ArrayList<>();

                if (activity.containsKey("locationCode")) {
                    JsonObject location = activity.getJsonObject("locationCode");
                    countries.add(location.getString("country", ""));
                }

                if (activity.containsKey("googlePlace")) {
                    JsonObject place = activity.getJsonObject("googlePlace");
                    String city = place.getString("city", "");
                    if (!city.isEmpty()) {
                        cities.add(city);
                    }
                    // Use country from googlePlace if locationCode not available
                    if (countries.isEmpty()) {
                        countries.add(place.getString("countryCode", ""));
                    }
                }

                product.setCities(cities);
                product.setCountries(countries);

                products.add(product);
            }
        }

        return products;
    }

    private List<BasicProductInfo> fetchBokunProducts(SearchProductRequest request) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // Build base URL
        StringBuilder pathBuilder = new StringBuilder("/activity.json/search");

        HttpURLConnection connection = createHttpConnection("POST", pathBuilder.toString());

        JsonObjectBuilder textFilterBuilder = Json.createObjectBuilder()
            .add("operator", "string")
            .add("searchExternalId", true)
            .add("searchFullText", true)
            .add("searchKeywords", true)
            .add("searchTitle", true)
            .add("wildcard", true);

        if (request.getProductName() != null && !request.getProductName().isEmpty()) {
            textFilterBuilder.add("text", request.getProductName());
        } else {
            textFilterBuilder.add("text", "");
        }

        JsonObjectBuilder requestBuilder = Json.createObjectBuilder()
            .add("textFilter", textFilterBuilder);

        // Write request body
        try (OutputStream os = connection.getOutputStream();
            JsonWriter writer = Json.createWriter(os)) {
            writer.writeObject(requestBuilder.build());
        }

        // 4. Process the response
        try {
            if (connection.getResponseCode() == 200) {
                return parseResponse(connection.getInputStream());
            } else {
                throw new IOException("API request failed: " + connection.getResponseCode() +
                    " - " + readErrorStream(connection));
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readErrorStream(HttpURLConnection connection) throws IOException {
        try (InputStream es = connection.getErrorStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(es))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    // Helper method to convert a weekday JSON object to OpeningHoursWeekday
    private OpeningHoursWeekday convertWeekdayFromJson(JsonObject weekdayJson) {
        if (weekdayJson == null) {
            return null;
        }
        
        OpeningHoursWeekday weekday = new OpeningHoursWeekday();
        weekday.setOpen24Hours(weekdayJson.getBoolean("open24Hours", false));
        
        if (weekdayJson.containsKey("timeIntervals")) {
            JsonArray intervalsArray = weekdayJson.getJsonArray("timeIntervals");
            List<OpeningHoursTimeInterval> intervals = new ArrayList<>();
            
            for (JsonValue intervalValue : intervalsArray) {
                JsonObject intervalJson = (JsonObject) intervalValue;
                OpeningHoursTimeInterval interval = new OpeningHoursTimeInterval();
                
                // Convert time array to string (e.g., [9,30] -> "09:30")
                JsonArray openFromArray = intervalJson.getJsonArray("openFrom");
                if (openFromArray != null && openFromArray.size() >= 2) {
                    int hour = openFromArray.getInt(0, 0);
                    int minute = openFromArray.getInt(1, 0);
                    interval.setOpenFrom(String.format("%02d:%02d", hour, minute));
                }
                
                interval.setOpenForHours(intervalJson.getInt("openForHours", 0));
                interval.setOpenForMinutes(intervalJson.getInt("openForMinutes", 0));
                
                // Handle duration/frequency
                if (intervalJson.containsKey("frequency"))  {
                    JsonValue frequencyValue = intervalJson.get("frequency");
                    if (frequencyValue.getValueType() == JsonValue.ValueType.OBJECT) {
                        JsonObject frequencyJson = intervalJson.getJsonObject("frequency");
                        Duration duration = new Duration();
                        duration.setMinutes(frequencyJson.getInt("minutes", 0));
                        duration.setHours(frequencyJson.getInt("hours", 0));
                        duration.setDays(frequencyJson.getInt("days", 0));
                        duration.setWeeks(frequencyJson.getInt("weeks", 0));
                        interval.setDuration(duration);
                    }
                }
                
                intervals.add(interval);
                
            }
            
            weekday.setTimeIntervals(intervals);
        }
        
        return weekday;
    }
    /**
     * Return detailed information about one particular product by given ID.
     */
     private ProductDescription parseProductDescription(InputStream inputStream) throws IOException {
        try (JsonReader reader = Json.createReader(inputStream)) {
            JsonObject productJson = reader.readObject();
            ProductDescription product = new ProductDescription();

            // Basic information
            // Handle numeric ID
            if (productJson.containsKey("id")) {
                JsonValue idValue = productJson.get("id");
                if (idValue.getValueType() == JsonValue.ValueType.NUMBER) {
                    product.setId(String.valueOf(((JsonNumber)idValue).intValue()));
                } else {
                    product.setId(productJson.getString("id", ""));
                }
            }

            if (productJson.containsKey("title")) {
                product.setName(productJson.getString("title"));
            }

            if (productJson.containsKey("vendor")) {
                if (productJson.getJsonObject("vendor").containsKey("title")) {
                    product.setDescription(productJson.getJsonObject("vendor").getString("title"));
                }
            } else {
                if (productJson.containsKey("description")) {
                    product.setDescription(productJson.getString("description"));
                } else {
                    product.setDescription("");
                }
            }
            
            // Pricing categories
            if (productJson.containsKey("pricingCategories")) {
                List<PricingCategory> categories = new ArrayList<>();
                for (JsonValue cat : productJson.getJsonArray("pricingCategories")) {
                    JsonObject category = (JsonObject) cat;
                    PricingCategory pc = new PricingCategory();

                    // Handle numeric ID
                    if (category.containsKey("id")) {
                        JsonValue idValue = category.get("id");
                        if (idValue.getValueType() == JsonValue.ValueType.NUMBER) {
                            pc.setId(String.valueOf(((JsonNumber)idValue).intValue()));
                        } else {
                            pc.setId(productJson.getString("id", ""));
                        }
                    }

                    pc.setLabel(category.getString("title"));
                    pc.setMinAge(category.getInt("minAge", 0));
                    pc.setMaxAge(category.getInt("maxAge", 0));
                    categories.add(pc);
                }
                product.setPricingCategories(categories);
            }

            // Rates
            if (productJson.containsKey("rates")) {
                List<Rate> rates = new ArrayList<>();
                for (JsonValue rate : productJson.getJsonArray("rates")) {
                    JsonObject rateJson = (JsonObject) rate;
                    Rate r = new Rate();

                    if (rateJson.containsKey("id")) {
                        JsonValue idValue = rateJson.get("id");
                        if (idValue.getValueType() == JsonValue.ValueType.NUMBER) {
                            r.setId(String.valueOf(((JsonNumber)idValue).intValue()));
                        } else {
                            r.setId(productJson.getString("id", ""));
                        }
                    }

                    r.setLabel(rateJson.getString("title"));
                    rates.add(r);
                }
                product.setRates(rates);
            }

            // Migrate allYearOpeningHours (defaultOpeningHours in JSON)
            if (productJson.containsKey("defaultOpeningHours")) {
                JsonValue defaultOpeningHoursValue = productJson.getValue("defaultOpeningHours");
                
                if (defaultOpeningHoursValue.getValueType() == JsonValue.ValueType.OBJECT) {
                    JsonObject defaultOpeningHoursJson = productJson.getJsonObject("defaultOpeningHours");
    
                    OpeningHours allYearOpeningHours = new OpeningHours();
                    
                    // Convert each weekday
                    allYearOpeningHours.setMonday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("monday")));
                    allYearOpeningHours.setTuesday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("tuesday")));
                    allYearOpeningHours.setWednesday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("wednesday")));
                    allYearOpeningHours.setThursday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("thursday")));
                    allYearOpeningHours.setFriday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("friday")));
                    allYearOpeningHours.setSaturday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("saturday")));
                    allYearOpeningHours.setSunday(convertWeekdayFromJson(defaultOpeningHoursJson.getJsonObject("sunday")));
                    
                    product.setAllYearOpeningHours(allYearOpeningHours);
                }
            }

            // Migrate seasonalOpeningHours
            if (productJson.containsKey("seasonalOpeningHours")) {
                JsonArray seasonalHoursArray = productJson.getJsonArray("seasonalOpeningHours");
                SeasonalOpeningHourSet seasonalOpeningHours = new SeasonalOpeningHourSet();
                
                for (JsonValue seasonalValue : seasonalHoursArray) {
                    SeasonalOpeningHours seasonal = new SeasonalOpeningHours();

                    if (seasonalValue.getValueType() == JsonValue.ValueType.OBJECT) {
                        JsonObject seasonalJson = (JsonObject) seasonalValue;
                       
                        OpeningHours openingHours = new OpeningHours();
    
                        // Convert each weekday
                        openingHours.setMonday(convertWeekdayFromJson(seasonalJson.getJsonObject("monday")));
                        openingHours.setTuesday(convertWeekdayFromJson(seasonalJson.getJsonObject("tuesday")));
                        openingHours.setWednesday(convertWeekdayFromJson(seasonalJson.getJsonObject("wednesday")));
                        openingHours.setThursday(convertWeekdayFromJson(seasonalJson.getJsonObject("thursday")));
                        openingHours.setFriday(convertWeekdayFromJson(seasonalJson.getJsonObject("friday")));
                        openingHours.setSaturday(convertWeekdayFromJson(seasonalJson.getJsonObject("saturday")));
                        openingHours.setSunday(convertWeekdayFromJson(seasonalJson.getJsonObject("sunday")));
                        
                        // Set seasonal dates
                        seasonal.setStartMonth(seasonalJson.getInt("startMonth", 0));
                        seasonal.setStartDay(seasonalJson.getInt("startDay", 0));
                        seasonal.setEndMonth(seasonalJson.getInt("endMonth", 0));
                        seasonal.setEndDay(seasonalJson.getInt("endDay", 0));
                        seasonal.setOpeningHours(openingHours);
                        
                        seasonalOpeningHours.addSeasonalOpeningHoursItem(seasonal);
                    }                    
                }
                
                product.setSeasonalOpeningHours(seasonalOpeningHours);
            }

            // Booking Type
            if (productJson.containsKey("bookingType")) {
                product.setBookingType(BookingType.fromValue(productJson.getString("bookingType")));

                if (product.getBookingType() == BookingType.DATE_AND_TIME) {
                    // Should parse startTimes
                    if (productJson.containsKey("startTimes")) {
                        JsonArray startTimesArray = productJson.getJsonArray("startTimes");
                        List<Time> startTimes = new ArrayList<>();

                        for (JsonValue value : startTimesArray) {
                            JsonObject startTimeJson = value.asJsonObject();
                            Time time = new Time();

                            if (startTimeJson.containsKey("hour")) {
                                time.setHour(startTimeJson.getInt("hour"));
                            }
                            if (startTimeJson.containsKey("minute")) {
                                time.setMinute(startTimeJson.getInt("minute"));
                            }

                            startTimes.add(time);
                        }

                        product.setStartTimes(startTimes);
                    }
                }
            }

            // customPickupAllowed
            if (productJson.containsKey("customPickupAllowed")) {
                product.setCustomPickupPlaceAllowed(productJson.getBoolean("customPickupAllowed", false));
            }

            // pickupMinutesBefore
            product.setPickupMinutesBefore(productJson.getInt("pickupMinutesBefore", 0));

            // pickupPlaces
            if (productJson.containsKey("startPoints")) {
                List<PickupDropoffPlace> pickupPlaces = new ArrayList<>();

                for (JsonValue point : productJson.getJsonArray("startPoints")) {
                    JsonObject pointJson = (JsonObject) point;
                    PickupDropoffPlace place = new PickupDropoffPlace();

                    place.setTitle(pointJson.getString("title", ""));

                    if (pointJson.containsKey("address")) {
                        JsonObject addressJson = pointJson.getJsonObject("address");
                        Address address = new Address();
                        address.setAddressLine1(addressJson.getString("addressLine1", ""));
                        address.setAddressLine2(addressJson.getString("addressLine2", ""));
                        address.setAddressLine3(addressJson.getString("addressLine3", ""));
                        address.setCity(addressJson.getString("city", ""));
                        address.setState(addressJson.getString("state", ""));
                        address.setPostalCode(addressJson.getString("postalCode", ""));
                        address.setCountryCode(addressJson.getString("countryCode", ""));

                        if (addressJson.containsKey("geoPoint")) {
                            JsonObject geoPointJson = addressJson.getJsonObject("geoPoint");
                            GeoPoint geoPoint = new GeoPoint();
                            geoPoint.setLatitude(geoPointJson.getJsonNumber("latitude").doubleValue());
                            geoPoint.setLongitude(geoPointJson.getJsonNumber("longitude").doubleValue());
                            address.setGeoPoint(geoPoint);
                        }

                        if (addressJson.containsKey("unLocode")) {
                            JsonObject unLocodeObject = addressJson.getJsonObject("unLocode");
                            UnLocode unLocode = new UnLocode();
                            unLocode.setCountry(unLocodeObject.getString("country", ""));
                            unLocode.setCity(unLocodeObject.getString("city", ""));

                            address.setUnLocode(unLocode);
                        }

                        place.setAddress(address);
                    }

                    pickupPlaces.add(place);
                }
                product.setPickupPlaces(pickupPlaces);
            }

            // dropoffAvailable
            if (productJson.containsKey("dropoffService")) {
                product.setDropoffAvailable(productJson.getBoolean("dropoffService", false));
            }

            // customDropoffAllowed
            if (productJson.containsKey("customDropoffAllowed")) {
                product.setCustomDropoffPlaceAllowed(productJson.getBoolean("customDropoffAllowed", false));
            }

            // dropoffPlaces


            // Product category
            if (productJson.containsKey("productCategory")) {
                product.setProductCategory(ProductCategory.fromValue(productJson.getString("productCategory")));
            }

            // Ticket support
            if (productJson.containsKey("ticketPerPerson")) {
                TicketSupport ticketSupport = productJson.getBoolean("ticketPerPerson") ? TicketSupport.TICKET_PER_PERSON :TicketSupport.TICKET_PER_BOOKING;
                product.setTicketSupport(Collections.singletonList(ticketSupport));

                // Ticket Type
                if (productJson.containsKey("barcodeType")) {
                    TicketType ticketType;

                    if (productJson.getString("barcodeType").equals("QR_CODE"))
                        ticketType = TicketType.QR_CODE;
                    else if (productJson.getString("barcodeType").equals("DATA_MATRIX"))
                        ticketType = TicketType.DATA_MATRIX;
                    else
                        ticketType = TicketType.BINARY;

                    product.setTicketType(ticketType);
                }
            }

            // Location information
            if (productJson.containsKey("googlePlace")) {
                JsonObject location = productJson.getJsonObject("googlePlace");
                product.setCountries(Collections.singletonList(location.getString("country", "")));
                product.setCities(Collections.singletonList(location.getString("city", "")));
            }

            // Meeting type
            if (productJson.containsKey("meetingType")) {
                product.setMeetingType(MeetingType.fromValue(productJson.getString("meetingType")));
            }

            // enforcedLeadPassengerFields
            if (productJson.containsKey("passengerFields")) {
                List<ContactField> enforcedLeadPassengerFields = new ArrayList<>();

                for (JsonValue point : productJson.getJsonArray("passengerFields")) {
                    JsonObject fieldObject = point.asJsonObject();
                    if (fieldObject.getBoolean("required", false)) {
                        // If true
                        String field = fieldObject.getString("field", "");

                        if (field.equals("PHONE_NUMBER") ) {
                            enforcedLeadPassengerFields.add(ContactField.PHONE);
                        } else if (field.equals("PASSPORT_ID")) {
                            enforcedLeadPassengerFields.add(ContactField.PASSPORT_NUMBER);
                        } else {
                            if (ContactField.fromValue(field) != null)
                                enforcedLeadPassengerFields.add(ContactField.fromValue(field));
                        }
                    }
                }
            }

            // enforcedTravellerFields
            if (productJson.containsKey("mainContactFields")) {
                List<ContactField> enforcedTravellerFields = new ArrayList<>();

                for (JsonValue point : productJson.getJsonArray("mainContactFields")) {
                    JsonObject fieldObject = point.asJsonObject();
                    if (fieldObject.getBoolean("required", false)) {
                        // If true
                        String field = fieldObject.getString("field", "");

                        if (field.equals("PHONE_NUMBER") ) {
                            enforcedTravellerFields.add(ContactField.PHONE);
                        } else if (field.equals("PASSPORT_ID")) {
                            enforcedTravellerFields.add(ContactField.PASSPORT_NUMBER);
                        } else {
                            if (ContactField.fromValue(field) != null)
                            enforcedTravellerFields.add(ContactField.fromValue(field));
                        }
                    }
                }
            }

            // extras
            if (productJson.containsKey("bookableExtras")) {
                List<Extra> extras = new ArrayList<>();

                for (JsonValue arrayValue : productJson.getJsonArray("bookableExtras")) {
                    JsonObject bookableExtra = arrayValue.asJsonObject();
                    Extra extra = new Extra();

                    if (bookableExtra.containsKey("id")) {
                        extra.setId(bookableExtra.getJsonNumber("id").toString());
                    }
                    if (bookableExtra.containsKey("title")) {
                        extra.setId(bookableExtra.getString("title", ""));
                    }
                    if (bookableExtra.containsKey("information")) {
                        extra.setDescription(bookableExtra.getString("information", ""));
                    }
                    if (bookableExtra.containsKey("maxPerBooking")) {
                        extra.setMaxPerBooking(bookableExtra.getJsonNumber("maxPerBooking").intValue());
                    }
                    if (bookableExtra.containsKey("limitByPax")) {
                        extra.setLimitByPax(bookableExtra.getBoolean("limitByPax", false));
                    }
                    if (bookableExtra.containsKey("increasesCapacity")) {
                        extra.setIncreasesCapacity(bookableExtra.getBoolean("increasesCapacity", false));
                    }

                    extras.add(extra);
                }

                product.setExtras(extras);
            }

            return product;
        }
    }

    public void getProductById(HttpServerExchange exchange) {
        log.trace("In ::getProductById");

        GetProductByIdRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), GetProductByIdRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        StringBuilder pathBuilder = new StringBuilder("/activity.json/").append(request.getExternalId());

        try {
            HttpURLConnection connection = createHttpConnection("GET", pathBuilder.toString());

            try {
                if (connection.getResponseCode() == 200) {
                    ProductDescription product = parseProductDescription(connection.getInputStream());
                    exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json");
                    exchange.getResponseSender().send(new Gson().toJson(product));
                } else {
                    handleApiError(connection);
                }
            } finally {
                connection.disconnect();
            }

        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logError("Error while searching products: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        }

        log.trace("Out ::getProductById");
    }

    /**
     * A set of product ids provided, return their availability over given date range ("shallow" call).
     * This will return a subset of product IDs passed on via ProductAvailabilityRequest.
     * Note: even though request contains capacity and date range, for a matching product it is enough to have availabilities for *some* dates over
     * requested period. Subsequent GetProductAvailability request will clarify precise dates and capacities.
     */
    public void getAvailableProducts(HttpServerExchange exchange) {
        log.trace("In ::getAvailableProducts");

        try {
            // Parse request
            ProductsAvailabilityRequest request = new Gson().fromJson(
                new InputStreamReader(exchange.getInputStream()),
                ProductsAvailabilityRequest.class
            );
            Configuration configuration = Configuration.fromRestParameters(request.getParameters());

            // Validate request
            if (request.getExternalProductIds() == null || request.getExternalProductIds().isEmpty()) {
                throw new IllegalArgumentException("External product IDs are required");
            }

            List<ProductsAvailabilityResponse> responses = new ArrayList<>();
            DateYMD from = request.getRange().getFrom();
            DateYMD to = request.getRange().getTo();

            // Process each external product ID
            for (String externalId : request.getExternalProductIds()) {
                try {
                    // Build API path for each product
                    StringBuilder pathBuilder = new StringBuilder("/activity.json/")
                        .append(externalId)
                        .append("/availabilities")
                        .append("?includeSoldOut=false");

                    // Add date range if specified
                    if (from != null && to != null) {
                        pathBuilder.append(String.format(
                            "&start=%04d-%02d-%02d&end=%04d-%02d-%02d",
                            from.getYear(), from.getMonth(), from.getDay(),
                            to.getYear(), to.getMonth(), to.getDay()
                        ));
                    }

                    // Make API call
                    HttpURLConnection connection = createHttpConnection("GET", pathBuilder.toString());

                    try {
                        int statusCode = connection.getResponseCode();

                        if (statusCode == 200) {
                            ProductsAvailabilityResponse response = new ProductsAvailabilityResponse();
                            response.setActualCheckDone(true);
                            response.setProductId(externalId);

                            responses.add(response);
                        } else {
                            // Log error but continue with other products
                            logError("Failed to check availability for product " + externalId +
                                    ". Status: " + statusCode, null);

                            // Add response indicating failure
                            ProductsAvailabilityResponse errorResponse = new ProductsAvailabilityResponse();
                            errorResponse.setProductId(externalId);
                            errorResponse.setActualCheckDone(false);
                            responses.add(errorResponse);
                        }
                    } finally {
                        connection.disconnect();
                    }
                } catch (Exception e) {
                    logError("Error checking availability for product " + externalId, e);

                    // Add error response for this product
                    ProductsAvailabilityResponse errorResponse = new ProductsAvailabilityResponse();
                    errorResponse.setProductId(externalId);
                    errorResponse.setActualCheckDone(false);
                    responses.add(errorResponse);
                }
            }

            // Send aggregated response
            exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getResponseSender().send(new Gson().toJson(responses));

        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logError("Error in getAvailableProducts: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        } finally {
            log.trace("Out ::getAvailableProducts");
        }
    }

    public JsonArray getBokunActivityAvailabilities(String externalId, DateYMD from, DateYMD to) {
        String errorId = generateErrorId(); // Optional, for better logging context
        try {
            StringBuilder pathBuilder = new StringBuilder("/activity.json/")
                .append(externalId)
                .append("/availabilities?lang=EN");

            if (from != null && to != null) {
                pathBuilder.append(String.format("&start=%04d-%02d-%02d&end=%04d-%02d-%02d",
                    from.getYear(), from.getMonth(), from.getDay(),
                    to.getYear(), to.getMonth(), to.getDay()));
            }

            HttpURLConnection connection = createHttpConnection("GET", pathBuilder.toString());

            int statusCode = connection.getResponseCode();
            if (statusCode == 200) {
                try (JsonReader reader = Json.createReader(connection.getInputStream())) {
                    return reader.readArray();
                }
            } else {
                handleApiError(connection); // Will throw
            }
        } catch (Exception e) {
            logError("Failed to fetch Bokun activity availabilities [" + errorId + "]", e);
            throw new RuntimeException("AVAILABILITY_FETCH_ERROR_" + errorId, e);
        }

        return Json.createArrayBuilder().build(); // Return empty array as fallback
    }


    /**
     * Get availability of a particular product over a date range. This request should follow GetAvailableProducts and provide more details on
     * precise dates/times for each product as well as capacity for each date. This call, however, is for a single product only (as opposed to
     * {@link #getAvailableProducts(HttpServerExchange)} which checks many products but only does a basic shallow check.
     */
    public void getProductAvailability(HttpServerExchange exchange) {
        log.trace("In ::getProductAvailability");

        ProductAvailabilityRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), ProductAvailabilityRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // At this point you might want to call your external system to do the actual search and return data back.
        // Code below just provides some mocks.


        try {
            StringBuilder pathBuilder = new StringBuilder("/activity.json/").append(request.getProductId()).append("/availabilities?lang=EN");
            DateYMD from = request.getRange().getFrom();
            DateYMD to = request.getRange().getTo();

            if (from != null && to != null) {
                pathBuilder.append(String.format("&start=%04d-%02d-%02d&end=%04d-%02d-%02d", from.getYear(), from.getMonth(), from.getDay(),
                                    to.getYear(), to.getMonth(), to.getDay()));
            }

            HttpURLConnection connection = createHttpConnection("GET", pathBuilder.toString());

            try {
                if (connection.getResponseCode() == 200) {
                    try (JsonReader reader = Json.createReader(connection.getInputStream())) {
                        JsonArray availabilityItems = reader.readArray();
                        List<ProductAvailabilityWithRatesResponse> responses = new ArrayList<>();

                        if (!availabilityItems.isEmpty()) {
                            for (JsonValue item : availabilityItems) {
                                JsonObject availability = (JsonObject) item;
                                ProductAvailabilityWithRatesResponse response = new ProductAvailabilityWithRatesResponse();

                                // Set capacity
                                response.setCapacity(availability.getInt("availabilityCount", 0));

                                // Set date (convert from timestamp to DateYMD)
                                long timestamp = availability.getJsonNumber("date").longValue();
                                LocalDate localDate = Instant.ofEpochMilli(timestamp)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate();

                                DateYMD dateYMD = new DateYMD()
                                    .year(localDate.getYear())
                                    .month(localDate.getMonthValue())
                                    .day(localDate.getDayOfMonth());
                                response.setDate(dateYMD);

                                // Set time
                                String startTime = availability.getString("startTime", "00:00");
                                Time time = new Time()
                                    .hour(Integer.parseInt(startTime.split(":")[0]))
                                    .minute(Integer.parseInt(startTime.split(":")[1]));
                                response.setTime(time);

                                // Set rates
                                List<RateWithPrice> rates = new ArrayList<>();
                                JsonArray pricesByRate = availability.getJsonArray("pricesByRate");

                                for (JsonValue priceItem : pricesByRate) {
                                    JsonObject priceInfo = (JsonObject) priceItem;
                                    RateWithPrice rateWithPrice = new RateWithPrice();

                                    // Set rate ID
                                    rateWithPrice.setRateId(String.valueOf(priceInfo.getInt("activityRateId")));

                                    // Set prices per person
                                    JsonArray pricePerCategory = priceInfo.getJsonArray("pricePerCategoryUnit");
                                    PricePerPerson pricePerPerson = new PricePerPerson();
                                    PricePerBooking pricePerBooking = new PricePerBooking();

                                    for (JsonValue priceCategory : pricePerCategory) {
                                        JsonObject categoryPrice = (JsonObject) priceCategory;
                                        JsonObject amount = categoryPrice.getJsonObject("amount");

                                        PricingCategoryWithPrice pricingCategoryWithPrice = new PricingCategoryWithPrice();
                                        pricingCategoryWithPrice.setPricingCategoryId(categoryPrice.getJsonNumber("id").toString());

                                        Price price = new Price();
                                        price.setAmount(amount.getJsonNumber("amount").toString());
                                        price.setCurrency(amount.getString("currency", ""));

                                        pricingCategoryWithPrice.setPrice(price);

                                        pricePerPerson.addPricingCategoryWithPriceItem(pricingCategoryWithPrice);
                                    }

                                    // Assuming first price is the main price (adjust as needed)
                                    if (!pricePerPerson.getPricingCategoryWithPrice().isEmpty()) {
                                        pricePerBooking.setPrice(pricePerPerson.getPricingCategoryWithPrice().get(0).getPrice());
                                    }
                                    rateWithPrice.setPricePerBooking(pricePerBooking);
                                    rateWithPrice.setPricePerPerson(pricePerPerson);

                                    rates.add(rateWithPrice);
                                }

                                response.setRates(rates);
                                responses.add(response);
                            }

                            exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
                            String response = new Gson().toJson(responses);
                            // log.trace("Out ::getProductAvailability - Response: {}", response);
                            exchange.getResponseSender().send(response);
                        } else {
                            exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
                            exchange.getResponseSender().send(new Gson().toJson(ImmutableList.of()));
                        }
                    }
                }

            } finally {
                connection.disconnect();
            }

        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logError("Error while searching products: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        }

        log.trace("Out ::getProductAvailability");
    }

    /**
     * This call secures necessary resource(s), such as activity time slot which can later become a booking. The reservation should be held for some
     * limited time, and reverted back to being available if the booking is not confirmed.
     *
     * Only implement this method if {@link PluginCapability#RESERVATIONS} is among capabilities of your {@link PluginDefinition}.
     * Otherwise you are only required to implement {@link #createAndConfirmBooking(HttpServerExchange)} which does both
     * reservation and confirmation, this method can be left empty or non-overridden.
     */
    public void createReservation(HttpServerExchange exchange) {
        // body of this method can be left empty if reserve & confirm is only supported as a single step
        ReservationRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), ReservationRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        log.trace("In ::createReservation");

        // At this point you might want to call your external system to do the actual reservation and return data back.
        // Code below just provides some mocks.

        ReservationResponse response = new ReservationResponse();
        SuccessfulReservation reservation = new SuccessfulReservation();
        reservation.setReservationConfirmationCode(UUID.randomUUID().toString());
        response.setSuccessfulReservation(reservation);

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(response));
        log.trace("Out ::createReservation");
    }

    /**
     * This call cancels existing reservation -- if the booking was not yet confirmed.
     *
     * Only implement this method if {@link PluginCapability#RESERVATIONS} and {@link PluginCapability#RESERVATION_CANCELLATION} are among
     * capabilities of your {@link PluginDefinition}.
     */
    public void cancelReservation(HttpServerExchange exchange) {
        log.trace("In ::cancelReservation");

        // At this point you might want to call your external system to do the actual reservation and return data back.
        // Code below just provides some mocks.

        CancelReservationResponse response = new CancelReservationResponse();
        SuccessfulReservationCancellation greatSuccess = new SuccessfulReservationCancellation();
        response.setSuccessfulReservationCancellation(greatSuccess);

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(response));
        log.trace("Out ::cancelReservation");
    }

    /**
     * Once reserved, proceed with booking. This will be called in case if reservation has succeeded.
     *
     * Only implement this method if {@link PluginCapability#RESERVATIONS} is among capabilities of your {@link PluginDefinition}.
     * Otherwise you are only required to implement {@link #createAndConfirmBooking(HttpServerExchange)} which does both
     * reservation and confirmation, this method can be left empty or non-overridden.
     */
    public void confirmBooking(HttpServerExchange exchange) {
        // body of this method can be left empty if reserve & confirm is only supported as a single step
        log.trace("In ::confirmBooking");

        ConfirmBookingRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), ConfirmBookingRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // At this point you might want to call your external system to do the actual confirmation and return data back.
        // Code below just provides some mocks.

        processBookingSourceInfo(request.getReservationData().getBookingSource());
        String confirmationCode = UUID.randomUUID().toString();

        ConfirmBookingResponse response = new ConfirmBookingResponse();
        SuccessfulBooking successfulBooking = new SuccessfulBooking();
        successfulBooking.setBookingConfirmationCode(confirmationCode);
        Ticket ticket = new Ticket();
        QrTicket qrTicket = new QrTicket();
        qrTicket.setTicketBarcode(confirmationCode + "_ticket");
        ticket.setQrTicket(qrTicket);
        successfulBooking.setBookingTicket(ticket);
        response.setSuccessfulBooking(successfulBooking);

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(response));
        log.trace("Out ::confirmBooking");
    }

    public void amendBooking(HttpServerExchange exchange) {
        log.trace("In ::amendBooking");

        AmendBookingRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), AmendBookingRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // At this point you might want to call your external system to do the actual amendment and return data back.
        // Code below just provides some mocks.

        processBookingSourceInfo(request.getReservationData().getBookingSource());

        AmendBookingResponse response = new AmendBookingResponse();
        SuccessfulAmendment successfulAmendment = new SuccessfulAmendment();
        Ticket ticket = new Ticket();
        QrTicket qrTicket = new QrTicket();
        String ticketBarcode = request.getBookingConfirmationCode() + "_ticket_amended";
        qrTicket.setTicketBarcode(ticketBarcode);
        ticket.setQrTicket(qrTicket);
        successfulAmendment.setBookingTicket(ticket);
        successfulAmendment.setAmendmentConfirmationCode(ticketBarcode);
        response.setSuccessfulAmendment(successfulAmendment);

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(response));
        log.trace("Out ::amendBooking");
    }

    /**
     * Example code to get info about the booking initiator.
     * Here you can see which data is available in each bookingSource.getSegment() case
     * @param bookingSource bookinSource data structure that is provided in booking requests
     */
    void processBookingSourceInfo(BookingSource bookingSource) {
        log.trace("Sales segment: {}",
                bookingSource.getSegment().name());
        log.trace("Booking channel: {} '{}'", bookingSource.getBookingChannel().getId(), bookingSource.getBookingChannel().getTitle());
        switch (bookingSource.getSegment()) {
            case OTA:
                log.trace("OTA system: {}", bookingSource.getBookingChannel().getSystemType());
                break;
            case MARKETPLACE:
                log.trace(
                        "Reseller vendor: {} '{}' reg.no. {}",
                        bookingSource.getMarketplaceVendor().getId(),
                        bookingSource.getMarketplaceVendor().getTitle(),
                        bookingSource.getMarketplaceVendor().getCompanyRegistrationNumber()
                );
                break;
            case AGENT_AREA:
                log.trace(
                        "Booking agent: {} '{}' reg.no. {}",
                        bookingSource.getBookingAgent().getId(),
                        bookingSource.getBookingAgent().getTitle(),
                        bookingSource.getBookingAgent().getCompanyRegistrationNumber()
                );
                break;
            case DIRECT_OFFLINE:
                log.trace(
                        "Extranet user: {} '{}'",
                        bookingSource.getExtranetUser().getEmail(),
                        bookingSource.getExtranetUser().getFullName()
                );
                break;
        }
    }

    public JsonObject getActivityProductInfo(String externalId) {
        String path = "/activity.json/" + externalId;

        try {
            HttpURLConnection connection = createHttpConnection("GET", path);

            try {
                if (connection.getResponseCode() == 200) {
                    try (InputStream inputStream = connection.getInputStream();
                         JsonReader reader = Json.createReader(inputStream)) {

                        return reader.readObject();
                    }
                } else {
                    handleApiError(connection);
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            logError("Error while getting activity product info: ", e);
        }

        return Json.createObjectBuilder().build(); // Return empty JsonObject on failure
    }

    public Integer getStartTimeIdForTime(JsonObject productJson, Time targetTime) {
        try {
            if (productJson.containsKey("startTimes")) {
                JsonArray startTimesArray = productJson.getJsonArray("startTimes");

                for (JsonValue value : startTimesArray) {
                    JsonObject startTimeJson = value.asJsonObject();
                    int hour = startTimeJson.getInt("hour", -1);
                    int minute = startTimeJson.getInt("minute", -1);

                    if (hour == targetTime.getHour() && minute == targetTime.getMinute()) {
                        return startTimeJson.getInt("id", 0);
                    }
                }
            }
        } catch (Exception e) {
            logError("Error while matching startTimeId: ", e);
        }

        return 0; // No match found
    }

    public Integer getPickupDropOffPlaceId(JsonObject productJson, PickupDropoffPlace pickupDropOffPlace) {
        try {
            if (productJson.containsKey("startPoints")) {
                JsonArray startPointsArray = productJson.getJsonArray("startPoints");

                for (JsonValue value : startPointsArray) {
                    JsonObject pointJson = value.asJsonObject();
                    String title = pointJson.getString("title", "");

                    if (title.equalsIgnoreCase(pickupDropOffPlace.getTitle())) {
                        return pointJson.getInt("id", 0); // default to 0 if id missing
                    }
                }
            }
        } catch (Exception e) {
            logError("Error while matching pickup place ID: ", e);
        }

        return 0; // No match found
    }

    /**
     * Only implement this method if {@link PluginCapability#RESERVATIONS} is <b>NOT</b> among capabilities of your {@link PluginDefinition}.
     * Otherwise you are only required to implement both {@link #createReservation(HttpServerExchange)} and {@link
     * #confirmBooking(HttpServerExchange)} separately; this method should remain empty or non-overridden.
     */
    public void createAndConfirmBooking(HttpServerExchange exchange) {
        log.trace("In ::createAndConfirmBooking");          // should never happen

        CreateConfirmBookingRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), CreateConfirmBookingRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // At this point you might want to call your external system to do the actual reserve&confirm and return data back.
        // Code below just provides some mocks.
        processBookingSourceInfo(request.getReservationData().getBookingSource());

        try {
            ReservationData reservationData = request.getReservationData();
            JsonObjectBuilder bokunRequest = Json.createObjectBuilder();
            JsonObjectBuilder bookingRequest = Json.createObjectBuilder();

            // checkoutOption
            bokunRequest.add("checkoutOption", "CUSTOMER_FULL_PAYMENT");

            // paymentMethod
            bokunRequest.add("paymentMethod", "RESERVE_FOR_EXTERNAL_PAYMENT");

            // source
            bokunRequest.add("source", "DIRECT_REQUEST");

            // 1. Build ActivityRequest
            JsonObjectBuilder activityRequest = Json.createObjectBuilder();
            activityRequest.add("activityId", Long.parseLong(request.getReservationData().getProductId()));

            // Set rateId from the first reservation (assuming single rate for all passengers)
            if (!reservationData.getReservations().isEmpty()) {
                Reservation reservation = reservationData.getReservations().get(0);

                activityRequest.add("rateId", Long.parseLong(reservation.getRateId()));
            }

            // Format date (yyyy-MM-dd)
            DateYMD date = reservationData.getDate();
            activityRequest.add("date", String.format("%04d-%02d-%02d", date.getYear(), date.getMonth(), date.getDay()));

            // StartTimeId
            JsonObject productInfo = getActivityProductInfo(reservationData.getProductId());

            DateYMD targetDate = reservationData.getDate();
            JsonArray avails = getBokunActivityAvailabilities(
                reservationData.getProductId(),
                targetDate,
                targetDate
            );
            Time targetTime = reservationData.getTime();
            long targetEpochMillis = LocalDate.of(targetDate.getYear(), targetDate.getMonth(), targetDate.getDay())
                                            .atStartOfDay(ZoneOffset.UTC)
                                            .toInstant()
                                            .toEpochMilli();

            int matchedStartTimeId = 0;

            if (!avails.isEmpty()) {
                if (targetTime != null) {
                    String targetTimeStr = String.format("%02d:%02d", targetTime.getHour(), targetTime.getMinute());

                    for (JsonValue val : avails) {
                        JsonObject avail = val.asJsonObject();
                        long availDateMillis = avail.getJsonNumber("date").longValue();
                        String availStartTime = avail.getString("startTime");

                        if (availDateMillis == targetEpochMillis && targetTimeStr.equals(availStartTime)) {
                            matchedStartTimeId = avail.getInt("startTimeId");
                            break;
                        }
                    }
                } else {
                    for (JsonValue val : avails) {
                        JsonObject avail = val.asJsonObject();
                        long availDateMillis = avail.getJsonNumber("date").longValue();

                        if (availDateMillis == targetEpochMillis) {
                            matchedStartTimeId = avail.getInt("startTimeId");
                            break;
                        }
                    }
                }
            }

            activityRequest.add("startTimeId", matchedStartTimeId);  // 0 if no match

            // Pickup places
            if (reservationData.getPickupRequired() != null) {
                activityRequest.add("pickup", reservationData.getPickupRequired());
            } else {
                activityRequest.add("pickup", false);
            }

            if (reservationData.getCustomPickupPlace() != null) {
                activityRequest.add("pickupPlaceDescription", reservationData.getCustomPickupPlace());
            } else {
                activityRequest.add("pickupPlaceDescription", "");
            }

            if (reservationData.getPredefinedPickupPlace() != null) {
                activityRequest.add("pickupPlaceId", getPickupDropOffPlaceId(productInfo, reservationData.getPredefinedPickupPlace()));
            } else {
                activityRequest.add("pickupPlaceId", 0);
            }

            // Drop Off
            if (reservationData.getDropoffRequired() != null) {
                activityRequest.add("dropoff", reservationData.getDropoffRequired());
            } else {
                activityRequest.add("dropoff", false);
            }

            if (reservationData.getCustomDropoffPlace() != null) {
                activityRequest.add("dropoffPlaceDescription", reservationData.getCustomDropoffPlace());
            } else {
                activityRequest.add("dropoffPlaceDescription", "");
            }

            if (reservationData.getPredefinedDropoffPlace() != null) {
                activityRequest.add("dropoffPlaceId", getPickupDropOffPlaceId(productInfo, reservationData.getPredefinedDropoffPlace()));
            } else {
                activityRequest.add("dropoffPlaceId", 0);
            }

            if (reservationData.getNotes() != null) {
                activityRequest.add("note", reservationData.getNotes());
            }

            String currencyCode = "GBP";
            Double totalPrice = 0.0;

            JsonArrayBuilder passengersArray = Json.createArrayBuilder();

            for (Reservation reservation : reservationData.getReservations()) {
                for (Passenger passenger : reservation.getPassengers()) {
                    JsonObjectBuilder passengerJson = Json.createObjectBuilder();
                    passengerJson.add("pricingCategoryId", Long.parseLong(passenger.getPricingCategoryId()));

                    // Build passengerDetails (custom answers)
                    JsonArrayBuilder passengerDetails = Json.createArrayBuilder();
                    Contact contact = passenger.getContact();

                    if (contact.getFirstName() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "firstName")
                            .add("values", Json.createArrayBuilder().add(contact.getFirstName())));
                    }

                    if (contact.getLastName() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "lastName")
                            .add("values", Json.createArrayBuilder().add(contact.getLastName())));
                    }

                    if (contact.getEmail() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "email")
                            .add("values", Json.createArrayBuilder().add(contact.getEmail())));
                    }

                    if (contact.getTitle() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "title")
                            .add("values", Json.createArrayBuilder().add(contact.getTitle().toString())));
                    }

                    if (contact.getPhone() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "phoneNumber")
                            .add("values", Json.createArrayBuilder().add(contact.getPhone())));
                    }

                    if (contact.getLanguage() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "language")
                            .add("values", Json.createArrayBuilder().add(contact.getLanguage())));
                    }

                    if (contact.getNationality() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "nationality")
                            .add("values", Json.createArrayBuilder().add(contact.getNationality())));
                    }

                    if (contact.getCountry() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "country")
                            .add("values", Json.createArrayBuilder().add(contact.getCountry())));
                    }

                    if (contact.getGender() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "sex")
                            .add("values", Json.createArrayBuilder().add(contact.getGender().toString())));
                    }

                    if (contact.getAddress() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "address")
                            .add("values", Json.createArrayBuilder().add(contact.getAddress())));
                    }

                    if (contact.getPostCode() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "postCode")
                            .add("values", Json.createArrayBuilder().add(contact.getPostCode())));
                    }

                    if (contact.getOrganization() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "organization")
                            .add("values", Json.createArrayBuilder().add(contact.getOrganization())));
                    }

                    if (contact.getPassportNumber() != null) {
                        passengerDetails.add(Json.createObjectBuilder()
                            .add("questionId", "passportId")
                            .add("values", Json.createArrayBuilder().add(contact.getPassportNumber())));
                    }

                    passengerJson.add("passengerDetails", passengerDetails);

                    // Build extras
                    JsonArrayBuilder extrasArray = Json.createArrayBuilder();
                    if (passenger.getExtraBookings() != null) {
                        for (ExtraBooking extra : passenger.getExtraBookings()) {
                            JsonObjectBuilder extraJson = Json.createObjectBuilder();
                            extraJson.add("extraId", Long.parseLong(extra.getExtraId()));
                            extraJson.add("quantity", extra.getAmount());

                            // Optional: add answers for extras if any (not shown in your model)
                            extraJson.add("answers", Json.createArrayBuilder());

                            extrasArray.add(extraJson);
                        }
                    }

                    passengerJson.add("extras", extrasArray);
                    passengersArray.add(passengerJson);

                    if (passenger.getPricePerPassenger() != null) {
                        if (passenger.getPricePerPassenger().getAmount() != null)
                            totalPrice += Double.parseDouble(passenger.getPricePerPassenger().getAmount());

                        if (passenger.getPricePerPassenger().getCurrency() != null)
                            currencyCode = passenger.getPricePerPassenger().getCurrency();
                    }

                }
            }

            activityRequest.add("passengers", passengersArray);

            // directBooking
            JsonObjectBuilder directBooking = Json.createObjectBuilder();
            JsonArray activityBookings = Json.createArrayBuilder().add(activityRequest).build();
            directBooking.add("activityBookings",  activityBookings);
            bookingRequest.add("activityBookings", activityBookings);

            JsonArrayBuilder mainContactDetails = Json.createArrayBuilder();
            Contact customerContact = reservationData.getCustomerContact();

            System.out.print(customerContact.toString());

            if (customerContact.getFirstName() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "firstName"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getFirstName()));
                mainContactDetails.add(answer);
            }

            if (customerContact.getLastName() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "lastName"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getLastName()));
                mainContactDetails.add(answer);
            }

            if (customerContact.getEmail() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "email"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getEmail()));
                mainContactDetails.add(answer);
            }

            if (customerContact.getPhone() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "phoneNumber"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(formatPhoneNumber(customerContact.getPhone())));
                mainContactDetails.add(answer);
            }

            if (customerContact.getAddress() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "address"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getAddress()));
                mainContactDetails.add(answer);
            }

            if (customerContact.getPostCode() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "postCode"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getPostCode()));
                mainContactDetails.add(answer);
            }

            if (customerContact.getCountry() != null) {
                JsonObjectBuilder answer = Json.createObjectBuilder();
                answer.add("questionId", "country"); // use actual Bokun questionId
                answer.add("values", Json.createArrayBuilder().add(customerContact.getCountry()));
                mainContactDetails.add(answer);
            }


            JsonArray mainContactDetailsObject = mainContactDetails.build();
            directBooking.add("mainContactDetails", mainContactDetailsObject);
            bookingRequest.add("mainContactDetails", mainContactDetailsObject);

            if (reservationData.getPlatformId() != null) {
                directBooking.add("externalBookingReference", reservationData.getPlatformId());
            }

            // Add directBookign to bokunRequest
            bokunRequest.add("directBooking", directBooking);
            bokunRequest.add("sendNotificationToMainContact", true);

            bokunRequest.add("amount", totalPrice);
            bokunRequest.add("currency", currencyCode);

            System.out.println("Total Price" + totalPrice + currencyCode);

            // {
            //     JsonObjectBuilder answer = Json.createObjectBuilder();
            //     answer.add("questionId", "sendNotificationToMainContact"); // use actual Bokun questionId
            //     answer.add("values", Json.createArrayBuilder().add(false));
            //     mainContactDetails.add(answer);

            //     bokunRequest.add("checkoutOptionAnswers", Json.createArrayBuilder().add(answer));
            // }

            // Build base URL
            StringBuilder pathBuilder = new StringBuilder("/checkout.json/options/booking-request");
            HttpURLConnection connection = createHttpConnection("POST", pathBuilder.toString());

            // JsonObject builtRequest = bookingRequest.build();

            // // Convert to string
            // StringWriter stringWriter = new StringWriter();
            // try (JsonWriter jsonWriter = Json.createWriter(stringWriter)) {
            //     jsonWriter.writeObject(builtRequest);
            // }
            // String jsonString = stringWriter.toString();

            // Log it
            // log.info("Booking Request JSON: {}", jsonString);

            try {
                // Write request body
                try (OutputStream os = connection.getOutputStream();
                    JsonWriter writer = Json.createWriter(os)) {
                    writer.writeObject(bookingRequest.build());
                }

                if (connection.getResponseCode() == 200) {
                    // Ok, now return the result
                    InputStream responseStream = connection.getInputStream();

                    try (JsonReader reader = Json.createReader(responseStream)) {
                        JsonObject bookingRequestResponse = reader.readObject();

                        // Submit
                        pathBuilder = new StringBuilder("/checkout.json/submit");
                        connection = createHttpConnection("POST", pathBuilder.toString());

                        // Write request body
                        try (OutputStream os = connection.getOutputStream();
                            JsonWriter writer = Json.createWriter(os)) {
                            writer.writeObject(bokunRequest.build());
                        }

                        System.out.print("Sent request already ===================");

                        if (connection.getResponseCode() == 200) {
                            // Ok, now return the result
                            responseStream = connection.getInputStream();
                            System.out.print("Get input stream");

                            try (JsonReader submitResponseReader = Json.createReader(responseStream)) {
                                JsonObject submitResponse = submitResponseReader.readObject();
                                System.out.print("Read now");
                                System.out.print(submitResponse.toString());

                                // Extract confirmation code from Bokun response
                                JsonObject booking = submitResponse.getJsonObject("booking");
                                String confirmationCode = booking.getString("confirmationCode");
                                String bookingCurrency = booking.getString("currency");
                                JsonNumber bookingTotalPrice = booking.getJsonNumber("totalPrice");

                                // Call another request
                                pathBuilder = new StringBuilder("/checkout.json/confirm-reserved/").append(confirmationCode);
                                connection = createHttpConnection("POST", pathBuilder.toString());

                                JsonObjectBuilder confirmRequestBuilder = Json.createObjectBuilder();
                                confirmRequestBuilder.add("sendNotificationToMainContact", true);
                                confirmRequestBuilder.add("amount", bookingTotalPrice);
                                confirmRequestBuilder.add("currency", bookingCurrency);

                                // Write request body
                                try (OutputStream os = connection.getOutputStream();
                                    JsonWriter writer = Json.createWriter(os)) {
                                    writer.writeObject(confirmRequestBuilder.build());
                                }

                                System.out.println("Sent confirm request");

                                if (connection.getResponseCode() == 200) {
                                    ConfirmBookingResponse response = new ConfirmBookingResponse();
                                    SuccessfulBooking successfulBooking = new SuccessfulBooking();

                                    successfulBooking.setBookingConfirmationCode(confirmationCode);
                                    Ticket ticket = new Ticket();
                                    QrTicket qrTicket = new QrTicket();
                                    qrTicket.setTicketBarcode(confirmationCode + "_ticket");
                                    ticket.setQrTicket(qrTicket);
                                    successfulBooking.setBookingTicket(ticket);
                                    response.setSuccessfulBooking(successfulBooking);

                                    exchange.setStatusCode(connection.getResponseCode());
                                    exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
                                    exchange.getResponseSender().send(new Gson().toJson(response));
                                } else {
                                    handleApiError(connection);
                                }
                            }
                        } else {
                            handleApiError(connection);
                        }
                    }
                } else {
                    handleApiError(connection);
                }
            } finally {
                connection.disconnect();
            }
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logError("Error while booking product: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        }

        log.trace("Out ::getProductAvailability");
    }

    /**
     * Once booked, a booking may be cancelled using booking ref number.
     * If your system does not support booking cancellation, one of the current workarounds is to create a cancellation policy (on the Bokun end)
     * which offers no refund. Then a cancellation does not have any monetary effect.
     */
    public void cancelBooking(HttpServerExchange exchange) {
        log.trace("In ::cancelBooking");

        // Parse incoming request
        CancelBookingRequest request = new Gson().fromJson(
            new InputStreamReader(exchange.getInputStream()),
            CancelBookingRequest.class
        );
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // Validate required fields
        if (request.getBookingConfirmationCode() == null || request.getBookingConfirmationCode().isEmpty()) {
            throw new IllegalArgumentException("Booking confirmation code is required");
        }

        try {
            String bookingCode = request.getBookingConfirmationCode();
            String agentCode = request.getAgentCode() != null ? request.getAgentCode() : "";

            // Build API URL
            String apiPath = "/booking.json/cancel-booking/" + bookingCode;
            HttpURLConnection connection = createHttpConnection("POST", apiPath);

            // Build request body
            JsonObjectBuilder bokunRequest = Json.createObjectBuilder()
                .add("note", agentCode)
                .add("notify", false)
                .add("refund", true);

            try {
                // Write request body
                try (OutputStream os = connection.getOutputStream();
                     JsonWriter writer = Json.createWriter(os)) {
                    writer.writeObject(bokunRequest.build());
                }

                // Process response
                int statusCode = connection.getResponseCode();
                CancelBookingResponse response = new CancelBookingResponse();

                if (statusCode == 200) {
                    // Successful cancellation
                    response.setSuccessfulCancellation(new SuccessfulCancellation());
                    exchange.setStatusCode(statusCode);

                    // Send response
                    exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
                    exchange.getResponseSender().send(new Gson().toJson(response));
                } else {
                    // Failed cancellation
                    String errorResponse = readErrorStream(connection);
                    log.error("Cancellation failed with status {}: {}", statusCode, errorResponse);

                    FailedCancellation failedCancellation = new FailedCancellation();
                    failedCancellation.setCancellationError(errorResponse);
                    response.setFailedCancellation(failedCancellation);
                    exchange.setStatusCode(statusCode);
                }
            } finally {
                connection.disconnect();
            }
        } catch (IllegalArgumentException e) {
            log.error("Validation error in cancelBooking: {}", e.getMessage());
            exchange.setStatusCode(400);
            exchange.getResponseSender().send("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            log.error("Error in cancelBooking: ", e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("{\"error\":\"Internal server error\"}");
        }

        log.trace("Out ::cancelBooking");
    }

    private String formatPhoneNumber(String phone) {

        if (phone == null || phone.isEmpty()) {
            return "+447537183368";
        }
        // Remove all non-digit characters
        String digits = phone.replaceAll("\\D", "");

        // Prepend '+' if it looks like an international number
        if (!digits.startsWith("0") && !digits.startsWith("+")) {
            return "+" + digits;
        }
        return digits;
    }
}

