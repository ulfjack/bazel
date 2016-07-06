
import org.junit.runners.JUnit4;
import org.junit.runner.RunWith;
import org.junit.Test;

public class MyTest2 {
  @Test
  public void ipass() {
  }

  @Test
  public void ifail() {
    throw new NullPointerException();
  }
}
