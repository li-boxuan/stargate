package io.stargate.it.http.dynamo;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.*;
import io.stargate.it.driver.CqlSessionExtension;
import io.stargate.it.driver.CqlSessionSpec;
import io.stargate.it.http.ApiServiceExtension;
import io.stargate.it.http.ApiServiceSpec;
import net.jcip.annotations.NotThreadSafe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@NotThreadSafe
@ExtendWith(CqlSessionExtension.class)
@CqlSessionSpec()
@ExtendWith(ApiServiceExtension.class)
@ApiServiceSpec(parametersCustomizer = "buildApiServiceParameters")
public class DynamoApiItemTest extends BaseDynamoApiTest {

  @Test
  public void testCreateItem() {

    DynamoDB dynamoDB = new DynamoDB(proxyClient);
    Table table = dynamoDB.getTable("crud_sample_table");

    Map<String, Object> dict = new HashMap<>();
    dict.put("integerList", Arrays.asList(0, 1, 2));
    dict.put("stringList", Arrays.asList("aa", "bb"));
    dict.put("nullKey", null);
    dict.put("hashMap", new HashMap<>());
    dict.put("doubleSet", new HashSet<>(Arrays.asList(1.0, 2.0)));
    Item item =
        new Item()
            .withPrimaryKey("Id", 121, "sid", "sid002")
            .withNumber("Title", 123)
            .withString("ISBN", "121-1111111111")
            .withStringSet("Authors", new HashSet<String>(Arrays.asList("Author21", "Author 22")))
            .withNumber("Price", 20)
            .withString("Dimensions", "8.5x11.0x.75")
            .withNumber("PageCount", 500)
            .withBoolean("InPublication", true)
            .withString("ProductCategory", "Book")
            .withMap("Dict", dict);
    table.putItem(item);
  }
}
