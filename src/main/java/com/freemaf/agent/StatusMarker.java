
package com.freemaf.agent;

import javax.swing.JLabel;

public class StatusMarker extends JLabel {

    public enum State {

        PLANNING("СОСТАВЛЯЕТ ПЛАН"),

        WORKING("РАБОТАЕТ НАД ПРОЕКТОМ"),

        CALIBRATION("КАЛИБРОВКА"),

        PAUSED("НА ПАУЗЕ"),

        STOPPED("ОСТАНОВЛЕН");

        private final String label;

        State(String label) {

            this.label = label;

        }

        public String label() {

            return label;

        }

    }

    public StatusMarker() {

        setState(State.STOPPED);

    }

    public void setState(State state) {

        setText("Статус: " + state.label());

    }

}
