package net.kdt.pojavlaunch.customcontrols.gamepad;


import android.content.Context;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.math.MathUtils;

import net.kdt.pojavlaunch.LWJGLGLFWKeycode;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.MCOptionUtils;

import org.lwjgl.glfw.CallbackBridge;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NONE;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.isJoystickEvent;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

public class Gamepad {

    /* Resolution scaler option, allow downsizing a window */
    private final float scaleFactor = LauncherPreferences.DEFAULT_PREF.getInt("resolutionRatio",100)/100f;
    /* Mouse positions, scaled by the scaleFactor */
    private float mouse_x, mouse_y;
    /* Sensitivity, adjusted according to screen size */
    private final double sensitivityFactor = (1.4 * (1080f/ currentDisplayMetrics.heightPixels));

    private final ImageView pointerView;

    private final GamepadDpad gamepadDpad = new GamepadDpad();

    private final GamepadJoystick leftJoystick;
    private int currentJoystickDirection = DIRECTION_NONE;

    private final GamepadJoystick rightJoystick;
    private float lastHorizontalValue = 0.0f;
    private float lastVerticalValue = 0.0f;

    private final double mouseMaxAcceleration = 2f;

    private double mouseMagnitude;
    private double mouseAngle;
    private double mouseSensitivity = 19;

    private final GamepadMap gameMap = GamepadMap.getDefaultGameMap();
    private final GamepadMap menuMap = GamepadMap.getDefaultMenuMap();
    private GamepadMap currentMap = gameMap;

    private boolean lastGrabbingState = true;
    //private final boolean mModifierDigitalTriggers;
    private final boolean mModifierAnalogTriggers;
    private boolean mModifierSwappedAxis = true; //Triggers and right stick axis are swapped.

    /* Choreographer with time to compute delta on ticking */
    private final Choreographer screenChoreographer;
    private long lastFrameTime;

    /* Listen for change in gui scale */
    private MCOptionUtils.MCOptionListener GUIScaleListener = () -> notifyGUISizeChange(getMcScale());

    public Gamepad(View contextView, InputDevice inputDevice){
        screenChoreographer = Choreographer.getInstance();
        Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                updateGrabbingState();
                tick(frameTimeNanos);
                screenChoreographer.postFrameCallback(this);
            }
        };
        screenChoreographer.postFrameCallback(frameCallback);
        lastFrameTime = System.nanoTime();

        /* Add the listener for the cross hair */
        MCOptionUtils.addMCOptionListener(GUIScaleListener);

        Toast.makeText(contextView.getContext(),"GAMEPAD CREATED", Toast.LENGTH_LONG).show();
        for(InputDevice.MotionRange range : inputDevice.getMotionRanges()){
            if(range.getAxis() == MotionEvent.AXIS_RTRIGGER
                    || range.getAxis() == MotionEvent.AXIS_LTRIGGER
                    || range.getAxis() == MotionEvent.AXIS_GAS
                    || range.getAxis() == MotionEvent.AXIS_BRAKE){
                mModifierSwappedAxis = false;
                break;
            }
        }

        leftJoystick = new GamepadJoystick(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, inputDevice);
        if(!mModifierSwappedAxis)
            rightJoystick = new GamepadJoystick(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, inputDevice);
        else
            rightJoystick = new GamepadJoystick(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, inputDevice);

       //mModifierDigitalTriggers = inputDevice.hasKeys(KeyEvent.KEYCODE_BUTTON_R2)[0];
        mModifierAnalogTriggers = supportAnalogTriggers(inputDevice);

        Context ctx = contextView.getContext();
        pointerView = new ImageView(contextView.getContext());
        pointerView.setImageDrawable(ResourcesCompat.getDrawable(ctx.getResources(), R.drawable.pointer, ctx.getTheme()));
        pointerView.getDrawable().setFilterBitmap(false);

        int size = (int) ((22 * getMcScale()) / scaleFactor);
        pointerView.setLayoutParams(new FrameLayout.LayoutParams(size, size));

        mouse_x = CallbackBridge.windowWidth/2;
        mouse_y = CallbackBridge.windowHeight/2;
        CallbackBridge.sendCursorPos(mouse_x, mouse_y);
        placePointerView(CallbackBridge.physicalWidth/2, CallbackBridge.physicalHeight/2);

        ((ViewGroup)contextView.getParent()).addView(pointerView);
    }

    /**
     * Send the new mouse position, computing the delta
     * @param frameTimeNanos The time to render the frame, used to compute mouse delta
     */
    public void tick(long frameTimeNanos){
        //update mouse position
        if(lastHorizontalValue != 0 || lastVerticalValue != 0){
            GamepadJoystick currentJoystick = lastGrabbingState ? leftJoystick : rightJoystick;

            double acceleration = (mouseMagnitude - currentJoystick.getDeadzone()) / (1 - currentJoystick.getDeadzone());
            acceleration = Math.pow(acceleration, mouseMaxAcceleration);
            if(acceleration > 1) acceleration = 1;

            // Compute delta since last tick time
            float deltaX = (float) (Math.cos(mouseAngle) * acceleration * mouseSensitivity);
            float deltaY = (float) (Math.sin(mouseAngle) * acceleration * mouseSensitivity);
            float deltaTimeScale = ((frameTimeNanos - lastFrameTime) / 16666666f); // Scale of 1 = 60Hz
            deltaX *= deltaTimeScale;
            deltaY *= deltaTimeScale;

            CallbackBridge.mouseX += deltaX;
            CallbackBridge.mouseY -= deltaY;

            if(!lastGrabbingState){
                CallbackBridge.mouseX = MathUtils.clamp(CallbackBridge.mouseX, 0, CallbackBridge.windowWidth);
                CallbackBridge.mouseY = MathUtils.clamp(CallbackBridge.mouseY, 0, CallbackBridge.windowHeight);
                placePointerView((int) (CallbackBridge.mouseX / scaleFactor), (int) (CallbackBridge.mouseY/ scaleFactor));
            }

            mouse_x = CallbackBridge.mouseX;
            mouse_y = CallbackBridge.mouseY;

            //Send the mouse to the game
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        }

        // Update last nano time
        lastFrameTime = frameTimeNanos;
    }

    /** Update the grabbing state, and change the currentMap, mouse position and sensibility */
    private void updateGrabbingState() {
        boolean lastGrabbingValue = lastGrabbingState;
        lastGrabbingState = CallbackBridge.isGrabbing();
        if(lastGrabbingValue == lastGrabbingState) return;

        // Switch grabbing state then
        currentMap.resetPressedState();
        if(lastGrabbingState){
            currentMap = gameMap;
            pointerView.setVisibility(View.INVISIBLE);
            mouseSensitivity = 18;
            return;
        }

        currentMap = menuMap;
        sendDirectionalKeycode(currentJoystickDirection, false, gameMap); // removing what we were doing

        mouse_x = CallbackBridge.windowWidth/2;
        mouse_y = CallbackBridge.windowHeight/2;
        CallbackBridge.sendCursorPos(mouse_x, mouse_y);
        placePointerView(CallbackBridge.physicalWidth/2, CallbackBridge.physicalHeight/2);
        pointerView.setVisibility(View.VISIBLE);
        // Sensitivity in menu is MC and HARDWARE resolution dependent
        mouseSensitivity = 19 * scaleFactor / sensitivityFactor;

    }

    public void update(KeyEvent event){
        sendButton(event);
    }

    public void update(MotionEvent event){
        updateDirectionalJoystick(event);
        updateMouseJoystick(event);
        updateAnalogTriggers(event);

        int[] dpadEvent = gamepadDpad.convertEvent(event);
        sendButton(dpadEvent[0], dpadEvent[1]);
    }

    private void updateMouseJoystick(MotionEvent event){
        GamepadJoystick currentJoystick = lastGrabbingState ? rightJoystick : leftJoystick;
        float horizontalValue = currentJoystick.getHorizontalAxis(event);
        float verticalValue = currentJoystick.getVerticalAxis(event);
        if(horizontalValue != lastHorizontalValue || verticalValue != lastVerticalValue){
            lastHorizontalValue = horizontalValue;
            lastVerticalValue = verticalValue;

            mouseMagnitude = currentJoystick.getMagnitude(event);
            mouseAngle = currentJoystick.getAngleRadian(event);

            tick(System.nanoTime());
            return;
        }
        lastHorizontalValue = horizontalValue;
        lastVerticalValue = verticalValue;

        mouseMagnitude = currentJoystick.getMagnitude(event);
        mouseAngle = currentJoystick.getAngleRadian(event);

    }

    private void updateDirectionalJoystick(MotionEvent event){
        GamepadJoystick currentJoystick = lastGrabbingState ? leftJoystick : rightJoystick;

        int lastJoystickDirection = currentJoystickDirection;
        currentJoystickDirection = currentJoystick.getHeightDirection(event);

        if(currentJoystickDirection == lastJoystickDirection) return;

        sendDirectionalKeycode(lastJoystickDirection, false, getCurrentMap());
        sendDirectionalKeycode(currentJoystickDirection, true, getCurrentMap());
    }

    private void updateAnalogTriggers(MotionEvent event){
        if(mModifierAnalogTriggers){
            getCurrentMap().TRIGGER_LEFT.update(
                    (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5)
                            || (event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5)
                            || (mModifierSwappedAxis &&(event.getAxisValue(MotionEvent.AXIS_Z) > 0.5)) );
            getCurrentMap().TRIGGER_RIGHT.update(
                    (event.getAxisValue( MotionEvent.AXIS_RTRIGGER) > 0.5)
                            || (event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5)
                            || (mModifierSwappedAxis && event.getAxisValue(MotionEvent.AXIS_RZ) > 0.5) );
        }
    }

    public void notifyGUISizeChange(int newSize){
        //Change the pointer size to match UI
        int size = (int) ((22 * newSize) / scaleFactor);
        pointerView.post(() -> pointerView.setLayoutParams(new FrameLayout.LayoutParams(size, size)));

    }

    /**
     * Detect if a gamepad supports analog triggers
     * @param inputDevice The input device with all the MotionRange
     * @return Whether the gamepad supports analog triggers
     */
    private boolean supportAnalogTriggers(InputDevice inputDevice){
        for(InputDevice.MotionRange motionRange : inputDevice.getMotionRanges()){
            int axis = motionRange.getAxis();

            if(     axis == MotionEvent.AXIS_BRAKE || axis == MotionEvent.AXIS_GAS ||
                    axis ==  MotionEvent.AXIS_LTRIGGER || axis == MotionEvent.AXIS_RTRIGGER ||
                    (mModifierSwappedAxis && axis == MotionEvent.AXIS_Z) ||
                    (mModifierSwappedAxis && axis == MotionEvent.AXIS_RZ)){
                return true;
            }
        }
        return false;
    }

    private GamepadMap getCurrentMap(){
        return currentMap;
    }

    private static void sendDirectionalKeycode(int direction, boolean isDown, GamepadMap map){
        switch (direction){
            case DIRECTION_NORTH:
                sendInput(map.DIRECTION_FORWARD, isDown);
                break;
            case DIRECTION_NORTH_EAST:
                sendInput(map.DIRECTION_FORWARD, isDown);
                sendInput(map.DIRECTION_RIGHT, isDown);
                break;
            case DIRECTION_EAST:
                sendInput(map.DIRECTION_RIGHT, isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                sendInput(map.DIRECTION_RIGHT, isDown);
                sendInput(map.DIRECTION_BACKWARD, isDown);
                break;
            case DIRECTION_SOUTH:
                sendInput(map.DIRECTION_BACKWARD, isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                sendInput(map.DIRECTION_BACKWARD, isDown);
                sendInput(map.DIRECTION_LEFT, isDown);
                break;
            case DIRECTION_WEST:
                sendInput(map.DIRECTION_LEFT, isDown);
                break;
            case DIRECTION_NORTH_WEST:
                sendInput(map.DIRECTION_FORWARD, isDown);
                sendInput(map.DIRECTION_LEFT, isDown);
                break;
        }
    }

    private void placePointerView(int x, int y){
        pointerView.setX(x - pointerView.getWidth()/2);
        pointerView.setY(y - pointerView.getHeight()/2);
    }

    public void sendButton(KeyEvent event){
        sendButton(event.getKeyCode(), event.getAction());
    }

    public void sendButton(int keycode, int action){
        boolean isDown = action == KeyEvent.ACTION_DOWN;
        switch (keycode){
            case KeyEvent.KEYCODE_BUTTON_A:
                getCurrentMap().BUTTON_A.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                getCurrentMap().BUTTON_B.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                getCurrentMap().BUTTON_X.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                getCurrentMap().BUTTON_Y.update(isDown);
                break;

                //Shoulders
            case KeyEvent.KEYCODE_BUTTON_L1:
                getCurrentMap().SHOULDER_LEFT.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                getCurrentMap().SHOULDER_RIGHT.update(isDown);
                break;

                //Triggers
            case KeyEvent.KEYCODE_BUTTON_L2:
                if(mModifierAnalogTriggers) break;
                getCurrentMap().TRIGGER_LEFT.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
                if(mModifierAnalogTriggers) break;
                getCurrentMap().TRIGGER_RIGHT.update(isDown);
                break;

                //L3 || R3
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                getCurrentMap().THUMBSTICK_LEFT.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                getCurrentMap().THUMBSTICK_RIGHT.update(isDown);
                break;

                //DPAD
            case KeyEvent.KEYCODE_DPAD_UP:
                getCurrentMap().DPAD_UP.update(isDown);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                getCurrentMap().DPAD_DOWN.update(isDown);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                getCurrentMap().DPAD_LEFT.update(isDown);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                getCurrentMap().DPAD_RIGHT.update(isDown);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                getCurrentMap().DPAD_RIGHT.update(false);
                getCurrentMap().DPAD_LEFT.update(false);
                getCurrentMap().DPAD_UP.update(false);
                getCurrentMap().DPAD_DOWN.update(false);
                break;

                //Start/select
            case KeyEvent.KEYCODE_BUTTON_START:
                getCurrentMap().BUTTON_START.update(isDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                getCurrentMap().BUTTON_SELECT.update(isDown);
                break;


            default:
                sendKeyPress(LWJGLGLFWKeycode.GLFW_KEY_SPACE, CallbackBridge.getCurrentMods(), isDown);
                break;
        }
    }

    public static void sendInput(int[] keycodes, boolean isDown){
        for(int keycode : keycodes){
            switch (keycode){
                case GamepadMap.MOUSE_SCROLL_DOWN:
                    if(isDown) CallbackBridge.sendScroll(0, -1);
                    break;
                case GamepadMap.MOUSE_SCROLL_UP:
                    if(isDown) CallbackBridge.sendScroll(0, 1);
                    break;

                case LWJGLGLFWKeycode.GLFW_MOUSE_BUTTON_RIGHT:
                    sendMouseButton(LWJGLGLFWKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                    break;
                case LWJGLGLFWKeycode.GLFW_MOUSE_BUTTON_LEFT:
                    sendMouseButton(LWJGLGLFWKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                    break;


                default:
                    sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isDown);
                    break;
            }
            CallbackBridge.setModifiers(keycode, isDown);
        }

    }

    public static boolean isGamepadEvent(MotionEvent event){
        return isJoystickEvent(event);
    }

    public static boolean isGamepadEvent(KeyEvent event){
        return ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || GamepadDpad.isDpadEvent(event) );
    }
}
