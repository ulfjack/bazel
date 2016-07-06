
import org.junit.runners.JUnit4;
import org.junit.runner.RunWith;
import org.junit.Test;

@RunWith(JUnit4.class)
public class MyTest {
  @Test
  public void ipass() {
  }

  @Test
  public void ifail() {
    throw new NullPointerException();
  }
}
