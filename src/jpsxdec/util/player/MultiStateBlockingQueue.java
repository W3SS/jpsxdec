/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2007-2010  Michael Sabin
 * All rights reserved.
 *
 * Redistribution and use of the jPSXdec code or any derivative works are
 * permitted provided that the following conditions are met:
 *
 *  * Redistributions may not be sold, nor may they be used in commercial
 *    or revenue-generating business activities.
 *
 *  * Redistributions that are modified from the original source must
 *    include the complete source code, including the source code for all
 *    components used by a binary built from the modified sources. However, as
 *    a special exception, the source code distributed need not include
 *    anything that is normally distributed (in either source or binary form)
 *    with the major components (compiler, kernel, and so on) of the operating
 *    system on which the executable runs, unless that component itself
 *    accompanies the executable.
 *
 *  * Redistributions must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or
 *    other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jpsxdec.util.player;

import java.util.Arrays;

/** Very powerful, thread-safe, blocking queue with the ability to specify exact 
 * behavior when taking and adding items.  */
public class MultiStateBlockingQueue<T> {

    private static final boolean DEBUG = false;

    public static enum ADD {
        IGNORE,
        BLOCK,
        BLOCK_WHEN_FULL,
        IGNORE_WHEN_FULL,
        OVERWRITE_WHEN_FULL
    }

    public static enum TAKE {
        IGNORE,
        BLOCK,
        BLOCK_WHEN_EMPTY,
        IGNORE_WHEN_EMPTY
    }

    private final Object _eventSync = new Object();
    
    private ADD _eAddingBehavior = ADD.BLOCK_WHEN_FULL;
    private TAKE _eTakingBehavior = TAKE.BLOCK;

    protected T[] _aoQueue;
    protected int _iHeadPos;
    protected int _iTailPos;
    private int _iSize;

    public MultiStateBlockingQueue(int iCapacity) {
        _aoQueue = (T[]) new Object[iCapacity];
        _iSize = _iHeadPos = _iTailPos = 0;
    }

    //////////////////////////////////

    public void play() {
        setAddTakeBehavior(ADD.BLOCK_WHEN_FULL, TAKE.BLOCK_WHEN_EMPTY);
    }

    // ...............
    public void overwriteWhenFull() {
        setAddBehavior(ADD.OVERWRITE_WHEN_FULL);
    }
    public void blockWhenFull() {
        setAddBehavior(ADD.BLOCK_WHEN_FULL);
    }
    // ...............

    public void stop() {
        setAddTakeBehavior(ADD.IGNORE, TAKE.IGNORE);
    }

    public void stopWhenEmpty() {
        setTakeBehavior(TAKE.IGNORE_WHEN_EMPTY);
    }

    public void pause() {
        setAddTakeBehavior(ADD.BLOCK_WHEN_FULL, TAKE.BLOCK);
    }

    public Object getSyncObject() {
        return _eventSync;
    }

    public boolean isPlaying() {
        synchronized (_eventSync) {
            return _eTakingBehavior == TAKE.BLOCK_WHEN_EMPTY || _eTakingBehavior == TAKE.IGNORE_WHEN_EMPTY;
        }
    }

    public boolean isStopped() {
        synchronized (_eventSync) {
            return _eTakingBehavior == TAKE.IGNORE ||
                    (_eTakingBehavior == TAKE.IGNORE_WHEN_EMPTY && isEmpty());
        }
    }

    public boolean isPaused() {
        synchronized (_eventSync) {
            return _eTakingBehavior == TAKE.BLOCK;
        }
    }

    /////////////////////////////////

    private void setAddBehavior(ADD iResponse) {
        synchronized (_eventSync) {
            _eAddingBehavior = iResponse;
            _eventSync.notifyAll();
        }
    }

    private void setTakeBehavior(TAKE iResponse) {
        synchronized (_eventSync) {
            _eTakingBehavior = iResponse;
            _eventSync.notifyAll();
        }
    }

    private void setAddTakeBehavior(ADD iAddResponse, TAKE iTakeResponse) {
        synchronized (_eventSync) {
            _eAddingBehavior = iAddResponse;
            _eTakingBehavior = iTakeResponse;
            _eventSync.notifyAll();
        }
    }

    /////////////////////////////////

    /** Returns true if object was added, or false if it wasn't.
     * This method may block. The object must not be null. */
    public boolean add(T o) throws InterruptedException {
        if (o == null)
            throw new IllegalArgumentException();

        if (DEBUG) System.out.println(Thread.currentThread().getName() + " add("+o.toString()+")");

        synchronized (_eventSync) {
            while (true) {
                if (_eAddingBehavior == ADD.IGNORE) {
                    if (DEBUG) System.out.println(Thread.currentThread().getName() + " stopped: returning false");
                    return false;
                } else if (_eAddingBehavior == ADD.BLOCK) {
                    _eventSync.wait();
                } else if (isFull()) {
                    switch (_eAddingBehavior) {
                        case IGNORE_WHEN_FULL:
                            return false;
                        case BLOCK_WHEN_FULL:
                            if (DEBUG) System.out.println(Thread.currentThread().getName() + " full: waiting");
                            _eventSync.wait();
                            break;
                        case OVERWRITE_WHEN_FULL:
                            if (DEBUG) System.out.println(Thread.currentThread().getName() + " adding (w/ overwrite) " + o.toString());
                            enqueue(o);
                            if (DEBUG) System.out.println(Thread.currentThread().getName() + " notifying other threads and returning");
                            _eventSync.notifyAll();
                            return true;
                        default:
                            throw new IllegalStateException();
                    }
                } else {
                    if (DEBUG) System.out.println(Thread.currentThread().getName() + " adding " + o.toString());
                    enqueue(o);
                    _iSize++;
                    if (DEBUG) System.out.println(Thread.currentThread().getName() + " notifying other threads and returning");
                    _eventSync.notifyAll();
                    return true;
                }
            }
        }
    }

    protected void enqueue(T o) {
        _aoQueue[_iTailPos] = o;
        _iTailPos++;
        if (_iTailPos >= _aoQueue.length) {
            _iTailPos = 0;
        }
    }

    /** Retrieves the head of the queue. May return null if no object is removed.
     * This method may block. */
    public T take() throws InterruptedException {
        if (DEBUG) System.out.println(Thread.currentThread().getName() + " enter take()");
        
        synchronized (_eventSync) {
            while (true) {
                if (_eTakingBehavior == TAKE.IGNORE) {
                    if (DEBUG) System.out.println(Thread.currentThread().getName() + " stopped: returning null");
                    return null;
                } else if (_eTakingBehavior == TAKE.BLOCK) {
                    if (DEBUG) System.out.println(Thread.currentThread().getName() + " paused: waiting");
                    _eventSync.wait();
                } else if (isEmpty()) {
                    switch (_eTakingBehavior) {
                        case IGNORE_WHEN_EMPTY:
                            return null;
                        case BLOCK_WHEN_EMPTY:
                            if (DEBUG) System.out.println(Thread.currentThread().getName() + " empty: waiting");
                            _eventSync.wait();
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                } else {
                    return dequeue();
                }
            }
        }
    }

    /** Always called within <code>syncronized (_eventSync)</code> */
    protected T dequeue() {
        T o = _aoQueue[_iHeadPos];
        if (DEBUG) System.out.println(Thread.currentThread().getName() + " removing object: " + o.toString());
        _aoQueue[_iHeadPos] = null;
        _iHeadPos++;
        if (_iHeadPos >= _aoQueue.length)
            _iHeadPos = 0;
        _iSize--;
        _eventSync.notifyAll();
        return o;
    }

    public boolean isFull() {
        synchronized (_eventSync) {
            return _iSize >= _aoQueue.length;
        }
    }

    public boolean isEmpty() {
        synchronized (_eventSync) {
            return _iSize <= 0;
        }
    }

    public void clear() {
        synchronized (_eventSync) {
            Arrays.fill(_aoQueue, null);
            _iSize = _iHeadPos = _iTailPos = 0;
            _eventSync.notifyAll();
        }
    }

    public Object peek() {
        synchronized (_eventSync) {
            if (isEmpty()) {
                return null;
            } else {
                return _aoQueue[_iHeadPos];
            }
        }
    }

    /////////////////////////////////////

    public int size() {
        synchronized (_eventSync) {
            return _iSize;
        }
    }

}