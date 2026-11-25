package com.axonivy.utils.rule.engine.dmn.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rules that the mapping and the generator share: FEEL names for fields, and the properties of a data class.
 */
class NamingTest {

  @Test
  void wordsReservedInFeel_getASuffix_andAreFoundBack() {
    assertThat(FeelNames.isReserved("for")).isTrue();
    assertThat(FeelNames.toFeelName("function")).isEqualTo("function_");
    assertThat(FeelNames.toFieldName("function_")).isEqualTo("function");
    assertThat(FeelNames.toFieldName("total_")).as("not a reserved word").isEqualTo("total_");
  }

  @Test
  void namesOfBuiltInFunctions_keepTheirName() {
    assertThat(FeelNames.toFeelName("count")).isEqualTo("count");
    assertThat(FeelNames.toFeelName("sum")).isEqualTo("sum");
  }

  @Test
  void charactersThatFeelDoesNotAllow_areReplaced() {
    assertThat(FeelNames.toFeelName("order-amount")).isEqualTo("order_amount");
    assertThat(FeelNames.toFeelName("1st")).isEqualTo("_1st");
    assertThat(FeelNames.toFeelName(" ")).isEqualTo("_");
  }

  @Test
  void fieldPaths_getTheShortestNameThatIsUnique() {
    assertThat(FeelNames.uniqueNames(List.of("in.invoice.customerTier", "in.invoice.orderAmount")))
        .containsExactly(entry("in.invoice.customerTier", "customerTier"), entry("in.invoice.orderAmount", "orderAmount"));
    assertThat(FeelNames.uniqueNames(List.of("in.member.type", "in.order.type")))
        .containsExactly(entry("in.member.type", "memberType"), entry("in.order.type", "orderType"));
  }

  @Test
  void dataClassProperties_areFoundInDeclarationOrder() {
    assertThat(BeanProperties.of(Line.class)).extracting(BeanProperties.Property::name)
        .containsExactly("product", "quantity", "amount");
    assertThat(BeanProperties.find(Line.class, "amount")).hasValueSatisfying(property -> assertThat(property.isWritable()).isFalse());

    var line = new Line();
    BeanProperties.find(Line.class, "quantity").orElseThrow().write(line, 4);
    assertThat(BeanProperties.find(Line.class, "quantity").orElseThrow().read(line)).isEqualTo(4);
  }

  private static java.util.Map.Entry<String, String> entry(String key, String value) {
    return java.util.Map.entry(key, value);
  }

  public static class Line {
    private String product;
    private Integer quantity;

    public String getProduct() {
      return product;
    }

    public void setProduct(String product) {
      this.product = product;
    }

    public Integer getQuantity() {
      return quantity;
    }

    public void setQuantity(Integer quantity) {
      this.quantity = quantity;
    }

    public Integer getAmount() {
      return quantity == null ? null : quantity * 10;
    }
  }
}
