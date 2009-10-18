package edu.cmu.sphinx.util.props;

/**
 * A global property of the sphinx configuration system
 *
 * @author Holger Brandl
 */
public class GlobalProperty {

    Object value;


    public GlobalProperty(Object value) {
        this.value = value;
    }


    public Object getValue() {
        GlobalProperty other = this;
        while (true) {
            if (other.value instanceof GlobalProperty) {
                other = ((GlobalProperty) other.value);
                continue;
            }

            return other.value;
        }
    }


    public void setValue(Object value) {
        this.value = value;
    }


    @Override
    public String toString() {
        return value != null ? value.toString() : null;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GlobalProperty)) return false;

        GlobalProperty that = (GlobalProperty) o;

//        if (value != null ? !value.equals(that.value) : that.value != null) return false;
        // note: will be fixed as soon as we have typed global properties

        return true;
    }


    @Override
    public int hashCode() {
        return (value != null ? value.hashCode() : 0);
    }
}
