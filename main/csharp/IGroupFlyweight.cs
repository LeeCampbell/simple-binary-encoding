﻿namespace Adaptive.SimpleBinaryEncoding
{
    /// <summary>
    /// Interface for repeating groups
    /// </summary>
    public interface IGroupFlyweight<out T>
    {
        /// <summary>
        /// Reset the flyweight to begin decoding from the current position.
        /// </summary>
        /// <param name="parentMessage">message this group belongs to.</param>
        /// <param name="buffer">the backing buffer to read from</param>
        /// <param name="actingVersion">version of the containing template being decoded.</param>
         void WrapForDecode(IMessageFlyweight parentMessage, DirectBuffer buffer, int actingVersion);

        /// <summary>
        ///  Reset the flyweight to begin encoding for the current position for a repeat count.
        /// </summary>
        /// <param name="parentMessage">message this group belongs to.</param>
        /// <param name="buffer">the backing buffer to write to.</param>
        /// <param name="count">count of the the times the groups will repeat.</param>
        void WrapForEncode(IMessageFlyweight parentMessage, DirectBuffer buffer, int count);

        /// <summary>
        /// Count of the times the group repeats.
        /// </summary>
        int Count { get; }

        /// <summary>
        /// Moves the flyweight to the next item in the group.
        /// </summary>
        /// <returns>returns the next value.</returns>
        T Next();
    }
}